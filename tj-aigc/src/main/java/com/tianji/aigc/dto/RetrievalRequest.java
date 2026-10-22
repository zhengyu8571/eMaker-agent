package com.tianji.aigc.dto;

import java.util.Set;

/**
 * 项目内部的统一知识库检索请求。
 */
public record RetrievalRequest(

        // 当前需要检索的问题
        String query,

        // 后端生成的对话ID，用于只读最近会话历史
        String conversationId,

        // 本次允许检索的知识库标识
        Set<String> libraryKeys
) {

    public RetrievalRequest {
        // 防止调用方在检索期间修改知识库范围
        libraryKeys = libraryKeys == null ? Set.of() : Set.copyOf(libraryKeys);
    }
}
