package com.tianji.aigc.service.impl;

import com.tianji.aigc.config.KnowledgeRetrievalProperties;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianji.aigc.dto.RetrievalRequest;
import com.tianji.aigc.dto.RetrievalResult;
import com.tianji.aigc.dto.RetrievalTrace;
import com.tianji.aigc.enums.RetrievalSource;
import com.tianji.aigc.enums.RetrievalStage;
import com.tianji.aigc.service.HybridRagService;
import com.tianji.aigc.service.KnowledgeRerankService;
import com.tianji.aigc.service.QueryRewriteService;
import com.tianji.aigc.support.retrieval.ParallelKnowledgeRetriever;
import com.tianji.aigc.support.retrieval.KnowledgeContextAssembler;
import com.tianji.aigc.support.retrieval.RrfFusion;
import com.tianji.common.exceptions.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * 编排问题改写、混合召回、精排和上下文裁剪。
 */
@Service
@RequiredArgsConstructor
public class HybridRagServiceImpl implements HybridRagService {

    // 将依赖历史的问题改写成独立检索问题
    private final QueryRewriteService queryRewriteService;

    // 并行执行向量和BM25召回
    private final ParallelKnowledgeRetriever parallelRetriever;

    // 按稳定chunkId融合并去重两路结果
    private final RrfFusion rrfFusion;

    // 使用DashScope重新排列RRF候选结果
    private final KnowledgeRerankService rerankService;

    // 按Token预算裁剪Chunk并生成可信来源
    private final KnowledgeContextAssembler contextAssembler;

    // 提供Query Rewrite和两路召回参数
    private final KnowledgeRetrievalProperties retrievalProperties;
    // 旧链路没有Runtime时也能记录完整检索输入输出
    @Autowired(required = false)
    private AiDebugTrace debugTrace;

    @Override
    public RetrievalResult retrieve(RetrievalRequest request) {
        // 调试关闭时沿用原检索方法
        if (this.debugTrace == null || !this.debugTrace.enabled()) {
            // 不额外生成检索记录
            return retrieveInternal(request);
        }
        // 保留原问题、会话和知识库范围
        var call = this.debugTrace.begin("RAG", "hybrid-retrieve", request);
        try {
            // 不改变问题重写、召回和排序
            var result = retrieveInternal(request);
            // 保存最终Chunk、引用编号和降级阶段
            this.debugTrace.end(call, result, null);
            // 返回原完整检索结果
            return result;
        } catch (RuntimeException exception) {
            // 检索失败仍保存原输入和直接原因
            this.debugTrace.end(call, null, exception);
            // 原Advisor继续负责拒答或降级
            throw exception;
        }
    }

    private RetrievalResult retrieveInternal(RetrievalRequest request) {
        validateRequest(request);

        var elapsedMillis = new EnumMap<RetrievalStage, Long>(RetrievalStage.class);

        // 先结合会话历史改写检索问题
        var startedAt = System.nanoTime();
        var normalizedQuery = this.queryRewriteService.rewrite(request.query(), request.conversationId());
        elapsedMillis.put(RetrievalStage.QUERY_REWRITE, elapsedSince(startedAt));

        // 同时执行Redis向量召回和ES BM25召回
        var recallResult = this.parallelRetriever.recall(normalizedQuery, request.libraryKeys());
        elapsedMillis.putAll(recallResult.elapsedMillis());

        // 使用两路排名计算RRF分数并按稳定chunkId去重
        startedAt = System.nanoTime();
        var rrfProperties = this.retrievalProperties.getRrf();
        var chunks = this.rrfFusion.fuse(
                recallResult.vectorChunks(),
                recallResult.bm25Chunks(),
                rrfProperties.getRankConstant(),
                rrfProperties.getTopK());
        elapsedMillis.put(RetrievalStage.RRF_FUSION, elapsedSince(startedAt));

        // 精排关闭或失败时继续使用上一步RRF结果
        startedAt = System.nanoTime();
        var rerankOutcome = this.rerankService.rerank(normalizedQuery, chunks);
        chunks = rerankOutcome.chunks();
        elapsedMillis.put(RetrievalStage.RERANK, elapsedSince(startedAt));

        var degradedStages = java.util.EnumSet.noneOf(RetrievalStage.class);
        degradedStages.addAll(recallResult.degradedStages());
        if (rerankOutcome.degraded()) {
            degradedStages.add(RetrievalStage.RERANK);
        }

        // 只为最终进入模型上下文的Chunk生成来源编号
        startedAt = System.nanoTime();
        var context = this.contextAssembler.assemble(chunks);
        chunks = context.chunks();
        elapsedMillis.put(RetrievalStage.CONTEXT_PRUNE, elapsedSince(startedAt));

        var trace = new RetrievalTrace(
                elapsedMillis,
                Map.of(
                        RetrievalSource.VECTOR, recallResult.vectorChunks().size(),
                        RetrievalSource.BM25, recallResult.bm25Chunks().size()),
                degradedStages);

        return new RetrievalResult(normalizedQuery, chunks, context.sources(), trace);
    }

    private void validateRequest(RetrievalRequest request) {
        // 统一入口拒绝无效问题和无限定范围的知识库查询
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new BadRequestException("知识库检索问题不能为空");
        }
        if (request.libraryKeys() == null || request.libraryKeys().isEmpty()) {
            throw new BadRequestException("知识库检索范围不能为空");
        }
    }

    private long elapsedSince(long startedAt) {
        // 将纳秒差值转换成便于日志和指标展示的毫秒数
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
