package com.tianji.aigc.repository;

import com.tianji.aigc.dto.RetrievedChunk;

import java.util.List;
import java.util.Set;

/**
 * 项目内部的知识库向量召回接口。
 */
public interface KnowledgeVectorRepository {

    /**
     * 在指定知识库范围内执行向量召回。
     */
    List<RetrievedChunk> search(String query,
                                Set<String> libraryKeys,
                                int topK,
                                double similarityThreshold);
}
