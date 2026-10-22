package com.tianji.aigc.dto;

/**
 * 触发知识库文档处理的最小消息。
 *
 * @param taskId 文档处理任务ID
 */
public record KnowledgeDocumentTaskMessage(Long taskId) {
}
