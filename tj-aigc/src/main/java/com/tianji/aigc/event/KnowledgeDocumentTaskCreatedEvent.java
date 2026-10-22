package com.tianji.aigc.event;

/**
 * 文档处理任务已经写入数据库。
 *
 * @param taskId 文档处理任务ID
 */
public record KnowledgeDocumentTaskCreatedEvent(Long taskId) {
}
