package com.tianji.aigc.support.retrieval;

import com.tianji.aigc.dto.RetrievalScore;
import com.tianji.aigc.dto.RetrievedChunk;
import com.tianji.aigc.enums.RetrievalSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 使用倒数排名融合两路召回结果。
 */
@Component
public class RrfFusion {

    /**
     * 按稳定 chunkId 去重，并计算 RRF 分数。
     */
    public List<RetrievedChunk> fuse(List<RetrievedChunk> vectorChunks,
                                     List<RetrievedChunk> bm25Chunks,
                                     int rankConstant,
                                     int topK) {
        var candidates = new LinkedHashMap<String, Candidate>();
        addCandidates(candidates, vectorChunks, RetrievalSource.VECTOR, rankConstant);
        addCandidates(candidates, bm25Chunks, RetrievalSource.BM25, rankConstant);

        // 融合分数相同时优先原始排名更靠前的Chunk
        return candidates.values().stream()
                .map(Candidate::toRetrievedChunk)
                .sorted(Comparator
                        .comparing(RetrievedChunk::fusionScore)
                        .reversed()
                        .thenComparingInt(this::bestRank)
                        .thenComparing(RetrievedChunk::chunkId))
                .limit(Math.max(1, topK))
                .toList();
    }

    private void addCandidates(LinkedHashMap<String, Candidate> candidates,
                               List<RetrievedChunk> chunks,
                               RetrievalSource source,
                               int rankConstant) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        var normalizedConstant = Math.max(1, rankConstant);
        for (var index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            if (chunk.chunkId() == null || chunk.chunkId().isBlank()) {
                continue;
            }

            // 优先使用召回引擎记录的排名，缺失时使用列表顺序
            var score = chunk.retrievalScores().getOrDefault(
                    source,
                    new RetrievalScore(index + 1, 0d));
            var candidate = candidates.computeIfAbsent(
                    chunk.chunkId(),
                    ignored -> new Candidate(chunk));
            candidate.add(source, score, normalizedConstant);
        }
    }

    private int bestRank(RetrievedChunk chunk) {
        return chunk.retrievalScores().values().stream()
                .mapToInt(RetrievalScore::rank)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private static class Candidate {

        // 两路同一chunkId共用首次出现的完整正文和元数据
        private final RetrievedChunk chunk;

        // 保存各召回来源的原始排名和分数
        private final EnumMap<RetrievalSource, RetrievalScore> scores =
                new EnumMap<>(RetrievalSource.class);

        // 累加1除以排名常数加排名
        private double fusionScore;

        private Candidate(RetrievedChunk chunk) {
            this.chunk = chunk;
        }

        private void add(RetrievalSource source,
                         RetrievalScore score,
                         int rankConstant) {
            // 同一路出现重复chunkId时只计算第一次
            if (this.scores.putIfAbsent(source, score) == null) {
                this.fusionScore += 1d / (rankConstant + Math.max(1, score.rank()));
            }
        }

        private RetrievedChunk toRetrievedChunk() {
            return new RetrievedChunk(
                    this.chunk.chunkId(),
                    this.chunk.content(),
                    this.chunk.libraryKey(),
                    this.chunk.libraryId(),
                    this.chunk.documentId(),
                    this.chunk.documentVersion(),
                    this.chunk.documentTitle(),
                    this.chunk.section(),
                    this.chunk.chunkOrdinal(),
                    this.chunk.tokenCount(),
                    this.scores,
                    this.fusionScore,
                    null);
        }
    }
}
