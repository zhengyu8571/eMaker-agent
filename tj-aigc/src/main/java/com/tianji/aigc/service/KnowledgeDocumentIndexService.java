package com.tianji.aigc.service;

/**
 * 将解析后的知识库文档写入检索索引。
 */
public interface KnowledgeDocumentIndexService {

    /**
     * 执行指定文档任务的完整索引流程。
     *
     * @param taskId 文档处理任务ID
     * @param recoverProcessing 是否接管上次未确认的处理中任务
     */
    void index(Long taskId, boolean recoverProcessing);
}
