package com.tianji.aigc.evaluation;

import com.tianji.aigc.config.KnowledgeRetrievalProperties;
import com.tianji.aigc.dto.RetrievalScore;
import com.tianji.aigc.dto.RetrievedChunk;
import com.tianji.aigc.enums.RetrievalSource;
import com.tianji.aigc.enums.RetrievalStage;
import com.tianji.aigc.repository.KnowledgeBm25Repository;
import com.tianji.aigc.repository.KnowledgeVectorRepository;
import com.tianji.aigc.service.KnowledgeRerankService;
import com.tianji.aigc.support.retrieval.KnowledgeContextAssembler;
import com.tianji.aigc.support.retrieval.ParallelKnowledgeRetriever;
import com.tianji.aigc.support.retrieval.RrfFusion;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仅供阶段3固定评测调用，不参与正常业务请求。
 */
@Profile("stage3-eval")
@RestController
@RequestMapping("/internal/stage3-evaluation")
@RequiredArgsConstructor
public class RagEvaluationController {

    // 由启动参数指定评测库，默认保留旧评测范围。
    @Value("${tj.ai.evaluation.library-key:java_test}")
    private String libraryKey;

    private final KnowledgeVectorRepository vectorRepository;
    private final KnowledgeBm25Repository bm25Repository;
    private final ParallelKnowledgeRetriever parallelRetriever;
    private final RrfFusion rrfFusion;
    private final KnowledgeRerankService rerankService;
    private final KnowledgeContextAssembler contextAssembler;
    private final KnowledgeRetrievalProperties properties;

    @PostMapping("/retrieve")
    public EvaluationResponse retrieve(@RequestBody EvaluationRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("评测问题不能为空");
        }

        var startedAt = System.nanoTime();
        var degradedStages = new ArrayList<String>();
        var chunks = switch (request.variant()) {
            case VECTOR_ONLY -> recallVector(request.query());
            case BM25_ONLY -> recallBm25(request.query());
            case RRF -> recallRrf(request.query(), degradedStages);
            case RRF_RERANK -> recallRrfAndRerank(request.query(), degradedStages);
        };

        // 排序候选用于检索指标，筛选结果用于上下文和无答案指标
        var rankedChunks = chunks.stream().map(EvaluationChunk::from).toList();
        var selectedChunks = this.contextAssembler.assemble(chunks).chunks();
        return new EvaluationResponse(
                request.variant(),
                elapsedMillis(startedAt),
                List.copyOf(degradedStages),
                rankedChunks,
                selectedChunks.stream().map(EvaluationChunk::from).toList());
    }

    private List<RetrievedChunk> recallVector(String query) {
        var vector = this.properties.getVectorRecall();
        return this.vectorRepository.search(
                query,
                Set.of(this.libraryKey),
                vector.getTopK(),
                vector.getSimilarityThreshold());
    }

    private List<RetrievedChunk> recallBm25(String query) {
        return this.bm25Repository.search(
                query,
                Set.of(this.libraryKey),
                this.properties.getBm25Recall().getTopK());
    }

    private List<RetrievedChunk> recallRrf(String query, List<String> degradedStages) {
        var recall = this.parallelRetriever.recall(query, Set.of(this.libraryKey));
        recall.degradedStages().stream()
                .map(Enum::name)
                .forEach(degradedStages::add);

        var rrf = this.properties.getRrf();
        return this.rrfFusion.fuse(
                recall.vectorChunks(),
                recall.bm25Chunks(),
                rrf.getRankConstant(),
                rrf.getTopK());
    }

    private List<RetrievedChunk> recallRrfAndRerank(String query, List<String> degradedStages) {
        var fusedChunks = recallRrf(query, degradedStages);
        var outcome = this.rerankService.rerank(query, fusedChunks);
        if (outcome.degraded()) {
            degradedStages.add(RetrievalStage.RERANK.name());
        }
        return outcome.chunks();
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    public record EvaluationRequest(EvaluationVariant variant, String query) {
        public EvaluationRequest {
            if (variant == null) {
                throw new IllegalArgumentException("评测档位不能为空");
            }
        }
    }

    public record EvaluationResponse(
            EvaluationVariant variant,
            long elapsedMillis,
            List<String> degradedStages,
            List<EvaluationChunk> rankedChunks,
            List<EvaluationChunk> selectedChunks) {
    }

    public record EvaluationChunk(
            String chunkId,
            String documentTitle,
            String section,
            Integer chunkOrdinal,
            Map<RetrievalSource, RetrievalScore> retrievalScores,
            Double fusionScore,
            Double rerankScore) {

        private static EvaluationChunk from(RetrievedChunk chunk) {
            return new EvaluationChunk(
                    chunk.chunkId(),
                    chunk.documentTitle(),
                    chunk.section(),
                    chunk.chunkOrdinal(),
                    chunk.retrievalScores(),
                    chunk.fusionScore(),
                    chunk.rerankScore());
        }
    }

    public enum EvaluationVariant {
        VECTOR_ONLY,
        BM25_ONLY,
        RRF,
        RRF_RERANK
    }
}
