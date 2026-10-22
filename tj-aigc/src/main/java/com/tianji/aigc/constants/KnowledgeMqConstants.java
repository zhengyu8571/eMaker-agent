package com.tianji.aigc.constants;

/**
 * 知识库文档处理使用的 RabbitMQ 名称。
 */
public final class KnowledgeMqConstants {

    // 接收知识库任务的持久化直连交换机
    public static final String DOCUMENT_EXCHANGE = "tj.aigc.document";

    // 保存等待处理的文档索引任务
    public static final String DOCUMENT_QUEUE = "doc.vectorize";

    // 将文档索引消息路由到处理队列
    public static final String DOCUMENT_INDEX_KEY = "document.vectorize";

    private KnowledgeMqConstants() {
    }
}
