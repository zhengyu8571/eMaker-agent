package com.tianji.aigc.dto;

import com.tianji.aigc.enums.RetrievalSource;

import java.util.Map;

/**
 * 项目内部的统一召回Chunk，不依赖具体检索引擎。
 */
public record RetrievedChunk(

        // 切块阶段生成的稳定ID
        String chunkId,

        // 交给模型使用的Chunk正文
        String content,

        // Chunk所属知识库标识
        String libraryKey,

        // Chunk所属知识库ID
        Long libraryId,

        // Chunk所属文档ID
        Long documentId,

        // Chunk所属文档版本
        Integer documentVersion,

        // 文档标题
        String documentTitle,

        // Chunk所属章节
        String section,

        // Chunk在文档中的序号
        Integer chunkOrdinal,

        // Chunk自身的Token数量
        Integer tokenCount,

        // 各召回来源对应的排名和原始分数
        Map<RetrievalSource, RetrievalScore> retrievalScores,

        // RRF融合后的分数，融合前为空
        Double fusionScore,

        // Rerank精排后的分数，精排前为空
        Double rerankScore
) {

    public RetrievedChunk {
        // 固定分数快照，避免后续阶段修改上游结果
        retrievalScores = retrievalScores == null ? Map.of() : Map.copyOf(retrievalScores);
    }
}
