package com.tianji.aigc.support.retrieval;

import com.tianji.aigc.config.KnowledgeRetrievalProperties;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianji.aigc.dto.RetrievedChunk;
import com.tianji.aigc.enums.RetrievalStage;
import com.tianji.aigc.repository.KnowledgeBm25Repository;
import com.tianji.aigc.repository.KnowledgeVectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 并行执行向量和 BM25 召回，并隔离单路失败。
 */
@Slf4j
@Component
public class ParallelKnowledgeRetriever {

    // 从Redis执行向量召回
    private final KnowledgeVectorRepository vectorRepository;

    // 从Elasticsearch执行BM25召回
    private final KnowledgeBm25Repository bm25Repository;

    // 读取两路召回参数、超时和线程池参数
    private final KnowledgeRetrievalProperties retrievalProperties;

    // 独立线程池避免占用公共ForkJoinPool
    private final ExecutorService retrievalExecutor;
    // 关联并行召回与其内部Embedding调用
    @Autowired(required = false)
    private AiDebugTrace debugTrace;

    public ParallelKnowledgeRetriever(
            KnowledgeVectorRepository vectorRepository,
            KnowledgeBm25Repository bm25Repository,
            KnowledgeRetrievalProperties retrievalProperties,
            @Qualifier("knowledgeRetrievalExecutor") ExecutorService retrievalExecutor) {
        this.vectorRepository = vectorRepository;
        this.bm25Repository = bm25Repository;
        this.retrievalProperties = retrievalProperties;
        this.retrievalExecutor = retrievalExecutor;
    }

    /**
     * 同时发起两路召回，并在共同截止时间内分别收集结果。
     */
    public RecallResult recall(String query, Set<String> libraryKeys) {
        var startedAt = System.nanoTime();
        var timeout = normalizeTimeout(
                this.retrievalProperties.getParallelRecall().getTimeout());
        var deadline = startedAt + timeout.toNanos();

        // 两个任务先全部提交，避免顺序等待造成串行调用
        var vectorFuture = submit(RetrievalStage.VECTOR_RECALL, Map.of("query", query, "libraryKeys", libraryKeys), () -> searchVector(query, libraryKeys));
        // 两路召回的原始结果分别保留，不只保存融合结果
        var bm25Future = submit(RetrievalStage.BM25_RECALL, Map.of("query", query, "libraryKeys", libraryKeys), () -> searchBm25(query, libraryKeys));

        var vectorAttempt = await(
                vectorFuture, RetrievalStage.VECTOR_RECALL, deadline, startedAt);
        var bm25Attempt = await(
                bm25Future, RetrievalStage.BM25_RECALL, deadline, startedAt);

        var elapsedMillis = new EnumMap<RetrievalStage, Long>(RetrievalStage.class);
        elapsedMillis.put(RetrievalStage.VECTOR_RECALL, vectorAttempt.elapsedMillis());
        elapsedMillis.put(RetrievalStage.BM25_RECALL, bm25Attempt.elapsedMillis());

        var degradedStages = EnumSet.noneOf(RetrievalStage.class);
        if (vectorAttempt.degraded()) {
            degradedStages.add(RetrievalStage.VECTOR_RECALL);
        }
        if (bm25Attempt.degraded()) {
            degradedStages.add(RetrievalStage.BM25_RECALL);
        }

        return new RecallResult(
                vectorAttempt.chunks(),
                bm25Attempt.chunks(),
                elapsedMillis,
                degradedStages);
    }

    private List<RetrievedChunk> searchVector(String query,
                                               Set<String> libraryKeys) {
        var properties = this.retrievalProperties.getVectorRecall();
        return this.vectorRepository.search(
                query,
                libraryKeys,
                properties.getTopK(),
                properties.getSimilarityThreshold());
    }

    private List<RetrievedChunk> searchBm25(String query,
                                            Set<String> libraryKeys) {
        var properties = this.retrievalProperties.getBm25Recall();
        return this.bm25Repository.search(
                query,
                libraryKeys,
                properties.getTopK());
    }

    private Future<RecallAttempt> submit(RetrievalStage stage, Object input, Supplier<List<RetrievedChunk>> action) {
        // 捕获提交任务时的请求上下文
        var observation = this.debugTrace == null ? null : this.debugTrace.currentObservation();
        try {
            // 模型调用不因切换线程而分散到不同文件
            return this.retrievalExecutor.submit(() -> {
                // 执行结束后自动恢复线程原上下文
                try (var ignored = this.debugTrace == null ? null : this.debugTrace.scope(observation)) {
                    // 保存召回输入输出并保持原降级逻辑
                    return execute(stage, input, action);
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池饱和时仅降级当前召回分支
            log.warn("知识库{}任务被线程池拒绝，本次跳过该分支", stage, e);
            return CompletableFuture.completedFuture(RecallAttempt.degraded(stage, 0L));
        }
    }

    private RecallAttempt execute(RetrievalStage stage, Object input, Supplier<List<RetrievedChunk>> action) {
        // 记录实际召回问题与知识库范围
        var call = this.debugTrace == null ? null : this.debugTrace.begin("RAG_RECALL", stage.name(), input);
        var startedAt = System.nanoTime();
        try {
            // 获取原始候选Chunk列表
            var chunks = action.get();
            // 融合前的排名和分数保留在对应调用中
            if (this.debugTrace != null) {
                // 调试结果不参与排序
                this.debugTrace.end(call, chunks, null);
            }
            // 返回原召回结果
            return RecallAttempt.success(stage, chunks, elapsedSince(startedAt));
        } catch (RuntimeException e) {
            // 业务选择降级时仍保留底层失败说明
            if (this.debugTrace != null) {
                // 不改变单路失败不阻断另一条的规则
                this.debugTrace.end(call, null, e);
            }
            // Redis或ES单路异常不阻断另一路结果
            log.warn("知识库{}执行失败，本次跳过该分支", stage, e);
            return RecallAttempt.degraded(stage, elapsedSince(startedAt));
        }
    }

    private RecallAttempt await(Future<RecallAttempt> future,
                                RetrievalStage stage,
                                long deadline,
                                long startedAt) {
        var remainingNanos = deadline - System.nanoTime();
        try {
            if (remainingNanos <= 0L && !future.isDone()) {
                throw new TimeoutException("召回超过共同截止时间");
            }
            return future.get(Math.max(1L, remainingNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // 请求中断底层任务，具体客户端是否立即停止由其实现决定
            future.cancel(true);
            log.warn("知识库{}超过召回时限，本次跳过该分支", stage);
            return RecallAttempt.degraded(stage, elapsedSince(startedAt));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("等待知识库{}时线程被中断，本次跳过该分支", stage);
            return RecallAttempt.degraded(stage, elapsedSince(startedAt));
        } catch (ExecutionException e) {
            log.warn("获取知识库{}结果失败，本次跳过该分支", stage, e.getCause());
            return RecallAttempt.degraded(stage, elapsedSince(startedAt));
        }
    }

    private Duration normalizeTimeout(Duration timeout) {
        // 非法配置回退为3秒
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Duration.ofSeconds(3);
        }
        return timeout;
    }

    private long elapsedSince(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * 保存两路召回结果及其运行状态。
     */
    public record RecallResult(
            List<RetrievedChunk> vectorChunks,
            List<RetrievedChunk> bm25Chunks,
            Map<RetrievalStage, Long> elapsedMillis,
            Set<RetrievalStage> degradedStages) {

        public RecallResult {
            vectorChunks = vectorChunks == null ? List.of() : List.copyOf(vectorChunks);
            bm25Chunks = bm25Chunks == null ? List.of() : List.copyOf(bm25Chunks);
            elapsedMillis = elapsedMillis == null ? Map.of() : Map.copyOf(elapsedMillis);
            degradedStages = degradedStages == null ? Set.of() : Set.copyOf(degradedStages);
        }
    }

    private record RecallAttempt(
            RetrievalStage stage,
            List<RetrievedChunk> chunks,
            long elapsedMillis,
            boolean degraded) {

        private static RecallAttempt success(RetrievalStage stage,
                                             List<RetrievedChunk> chunks,
                                             long elapsedMillis) {
            return new RecallAttempt(stage, chunks, elapsedMillis, false);
        }

        private static RecallAttempt degraded(RetrievalStage stage,
                                              long elapsedMillis) {
            return new RecallAttempt(stage, List.of(), elapsedMillis, true);
        }
    }
}
