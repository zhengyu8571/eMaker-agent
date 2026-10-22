package com.tianji.aigc.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 编排知识库文档处理流程。
 */
public interface KnowledgeDocumentProcessingService {

    /**
     * 根据任务准备可以写入索引的文档分块。
     *
     * @param taskId 文档处理任务ID
     * @return 带稳定ID和检索元数据的文档分块
     */
    List<Document> prepareChunks(Long taskId);
}
