package com.tianji.aigc.service;

/**
 * 项目内部的检索问题改写接口。
 */
public interface QueryRewriteService {

    /**
     * 结合最近会话历史，将当前问题改写成可独立检索的问题。
     *
     * @param question 当前用户问题
     * @param conversationId 后端生成的对话ID
     * @return 改写后的问题，失败时返回原问题
     */
    String rewrite(String question, String conversationId);
}
