package com.tianji.aigc.repository;

import com.tianji.aigc.dto.RetrievedChunk;

import java.util.List;
import java.util.Set;

/**
 * 项目内部的知识库 BM25 召回接口。
 */
public interface KnowledgeBm25Repository {

    /**
     * 在指定知识库范围内执行关键词召回。
     */
    List<RetrievedChunk> search(String query,
                                Set<String> libraryKeys,
                                int topK);
}
