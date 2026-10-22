package com.tianji.aigc.service.impl;

import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.tianji.aigc.config.KnowledgeRetrievalProperties;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianji.aigc.dto.RerankOutcome;
import com.tianji.aigc.dto.RetrievedChunk;
import com.tianji.aigc.service.KnowledgeRerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用 Spring AI Alibaba 的 DashScope RerankModel 完成精排。
 */
@Slf4j
@Service
public class DashScopeKnowledgeRerankService implements KnowledgeRerankService {

    // 延迟获取Spring AI Alibaba官方RerankModel
    private final ObjectProvider<RerankModel> rerankModelProvider;

    // 读取精排开关、模型名称和返回数量
    private final KnowledgeRetrievalProperties retrievalProperties;

    // 复用知识库检索线程池限制精排等待时间
    private final ExecutorService retrievalExecutor;
    // SDK精排没有通用模型观测时显式保留输入输出
    @Autowired(required = false)
    private AiDebugTrace debugTrace;

    public DashScopeKnowledgeRerankService(
            ObjectProvider<RerankModel> rerankModelProvider,
            KnowledgeRetrievalProperties retrievalProperties,
            @Qualifier("knowledgeRetrievalExecutor") ExecutorService retrievalExecutor) {
        this.rerankModelProvider = rerankModelProvider;
        this.retrievalProperties = retrievalProperties;
        this.retrievalExecutor = retrievalExecutor;
    }

    @Override
    public RerankOutcome rerank(String query, List<RetrievedChunk> chunks) {
        var properties = this.retrievalProperties.getRerank();
        if (!properties.isEnabled()) {
            // 关闭精排时直接保留RRF结果
            return RerankOutcome.degraded(chunks);
        }
        if (chunks == null || chunks.isEmpty()) {
            // 没有候选Chunk时无需调用远程模型
            return RerankOutcome.success(List.of());
        }

        try {
            var rerankModel = this.rerankModelProvider.orderedStream()
                    .findFirst()
                    .orElse(null);
            if (rerankModel == null) {
                log.warn("未找到DashScope RerankModel，本次保留RRF结果");
                return RerankOutcome.degraded(chunks);
            }

            var candidates = toDocuments(chunks);
            if (candidates.isEmpty()) {
                log.warn("没有可供DashScope Rerank处理的有效Chunk，本次保留RRF结果");
                return RerankOutcome.degraded(chunks);
            }
            var topN = Math.min(
                    Math.max(1, properties.getTopN()),
                    candidates.size());

            // RerankRequest和Options均来自Spring AI Alibaba官方API
            var options = DashScopeRerankOptions.builder()
                    .withModel(normalizeModel(properties.getModel()))
                    .withTopN(topN)
                    .withReturnDocuments(false)
                    .build();
            var response = callWithTimeout(
                    rerankModel,
                    new RerankRequest(query, candidates, options),
                    properties.getTimeout());
            if (response == null) {
                return RerankOutcome.degraded(chunks);
            }
            var rerankedChunks = mapResults(response.getResults(), chunks);

            if (rerankedChunks.isEmpty()) {
                log.warn("DashScope Rerank未返回有效Chunk，本次保留RRF结果");
                return RerankOutcome.degraded(chunks);
            }
            return RerankOutcome.success(rerankedChunks);
        } catch (RuntimeException e) {
            // 精排不可用时不影响已经完成的RRF结果
            log.warn("DashScope Rerank执行失败，本次保留RRF结果", e);
            return RerankOutcome.degraded(chunks);
        }
    }

    private List<Document> toDocuments(List<RetrievedChunk> chunks) {
        // 用稳定chunkId关联模型结果和项目内部Chunk
        return chunks.stream()
                .filter(chunk -> chunk.chunkId() != null
                        && !chunk.chunkId().isBlank()
                        && chunk.content() != null
                        && !chunk.content().isBlank())
                .map(chunk -> new Document(
                        chunk.chunkId(),
                        // 精排要看到来源上下文，不能只比较脱离文档的正文
                        rerankText(chunk),
                        Map.of("chunk_id", chunk.chunkId())))
                .toList();
    }

    private String rerankText(RetrievedChunk chunk) {
        // 标题用于识别用户指定的文档
        var title = chunk.documentTitle() == null ? "" : chunk.documentTitle();
        // 章节用于区分同文档的不同内容
        var section = chunk.section() == null ? "" : chunk.section();
        // 仅增强模型排序输入，不改原Chunk或最终引用正文
        return "文档：" + title + "\n章节：" + section + "\n正文：" + chunk.content();
    }

    private RerankResponse callWithTimeout(RerankModel rerankModel,
                                           RerankRequest request,
                                           Duration timeout) {
        // 精排输入包含实际问题、候选正文与模型参数
        var call = this.debugTrace == null ? null : this.debugTrace.begin("MODEL", "RERANK", request);
        // 关联精排线程中的调用与当前请求
        var observation = this.debugTrace == null ? null : this.debugTrace.currentObservation();
        Future<RerankResponse> future;
        try {
            // 将阻塞式官方模型调用放入独立检索线程池
            future = this.retrievalExecutor.submit(() -> {
                // 任务结束后恢复线程原上下文
                try (var ignored = this.debugTrace == null ? null : this.debugTrace.scope(observation)) {
                    // 保持官方精排模型调用
                    return rerankModel.call(request);
                }
            });
        } catch (RejectedExecutionException e) {
            // 拒绝提交也属于本次调用的失败
            if (this.debugTrace != null) {
                // 不改变保留RRF结果的策略
                this.debugTrace.end(call, null, e);
            }
            log.warn("知识库精排任务被线程池拒绝，本次保留RRF结果", e);
            return null;
        }

        try {
            var normalizedTimeout = normalizeTimeout(timeout);
            var response = future.get(normalizedTimeout.toMillis(), TimeUnit.MILLISECONDS);
            // 保存SDK返回的精排结果而不是只记录条数
            if (this.debugTrace != null) {
                // 排名和分数仍由原方法处理
                this.debugTrace.end(call, response, null);
            }
            // 返回原精排结果
            return response;
        } catch (TimeoutException e) {
            // 立即记录超时，不等待迟到响应
            if (this.debugTrace != null) {
                // 保留超时类型与耗时
                this.debugTrace.end(call, null, e);
            }
            // 超时后请求中断官方模型调用并立即回退
            future.cancel(true);
            log.warn("DashScope Rerank超过精排时限，本次保留RRF结果");
            return null;
        } catch (InterruptedException e) {
            // 保留取消或线程中断线索
            if (this.debugTrace != null) {
                // 不吞掉原中断恢复流程
                this.debugTrace.end(call, null, e);
            }
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("等待DashScope Rerank时线程被中断，本次保留RRF结果");
            return null;
        } catch (ExecutionException e) {
            // 保存SDK真正抛出的异常类型
            if (this.debugTrace != null) {
                // 不只记录线程池包装异常
                this.debugTrace.end(call, null, e.getCause() == null ? e : e.getCause());
            }
            log.warn("DashScope Rerank执行失败，本次保留RRF结果", e.getCause());
            return null;
        }
    }

    private List<RetrievedChunk> mapResults(
            List<com.alibaba.cloud.ai.document.DocumentWithScore> results,
            List<RetrievedChunk> chunks) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        var chunksById = new LinkedHashMap<String, RetrievedChunk>();
        for (var chunk : chunks) {
            if (chunk.chunkId() != null && !chunk.chunkId().isBlank()) {
                chunksById.putIfAbsent(chunk.chunkId(), chunk);
            }
        }

        var seenChunkIds = new LinkedHashSet<String>();
        var rerankedChunks = new ArrayList<RetrievedChunk>();
        for (var result : results) {
            var document = result.getOutput();
            if (document == null || !seenChunkIds.add(document.getId())) {
                continue;
            }
            var chunk = chunksById.get(document.getId());
            if (chunk != null) {
                rerankedChunks.add(withRerankScore(chunk, result.getScore()));
            }
        }
        return List.copyOf(rerankedChunks);
    }

    private RetrievedChunk withRerankScore(RetrievedChunk chunk, Double score) {
        // 保留召回分数和RRF分数，仅补充模型精排分数
        return new RetrievedChunk(
                chunk.chunkId(),
                chunk.content(),
                chunk.libraryKey(),
                chunk.libraryId(),
                chunk.documentId(),
                chunk.documentVersion(),
                chunk.documentTitle(),
                chunk.section(),
                chunk.chunkOrdinal(),
                chunk.tokenCount(),
                chunk.retrievalScores(),
                chunk.fusionScore(),
                normalizeScore(score));
    }

    private double normalizeScore(Double score) {
        return score != null && Double.isFinite(score) ? score : 0d;
    }

    private String normalizeModel(String model) {
        // 模型名称为空时使用当前DashScope文本精排模型
        return model == null || model.isBlank() ? "gte-rerank-v2" : model.strip();
    }

    private Duration normalizeTimeout(Duration timeout) {
        // 非法配置回退为5秒
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Duration.ofSeconds(5);
        }
        return timeout;
    }
}
