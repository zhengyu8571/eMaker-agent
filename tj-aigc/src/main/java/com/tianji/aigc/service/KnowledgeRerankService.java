package com.tianji.aigc.service;

import com.tianji.aigc.dto.RerankOutcome;
import com.tianji.aigc.dto.RetrievedChunk;

import java.util.List;

/**
 * 项目内部的知识库精排接口。
 */
public interface KnowledgeRerankService {

    /**
     * 根据问题重新评估并排列候选Chunk。
     */
    RerankOutcome rerank(String query, List<RetrievedChunk> chunks);
}
