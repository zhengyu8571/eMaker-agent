package com.tianji.aigc.service;

import com.tianji.aigc.dto.RetrievalRequest;
import com.tianji.aigc.dto.RetrievalResult;

/**
 * 项目内部的混合RAG统一入口。
 */
public interface HybridRagService {

    /**
     * 根据问题和知识库范围执行完整检索链。
     */
    RetrievalResult retrieve(RetrievalRequest request);
}
