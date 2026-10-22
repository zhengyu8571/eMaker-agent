package com.tianji.aigc.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.tianji.aigc.constants.KnowledgeMqConstants.DOCUMENT_EXCHANGE;
import static com.tianji.aigc.constants.KnowledgeMqConstants.DOCUMENT_INDEX_KEY;
import static com.tianji.aigc.constants.KnowledgeMqConstants.DOCUMENT_QUEUE;

/**
 * 声明知识库文档任务的 RabbitMQ 拓扑。
 */
@Configuration
public class KnowledgeMqConfig {

    @Bean
    public DirectExchange knowledgeDocumentExchange() {
        // 创建持久化直连交换机
        return ExchangeBuilder.directExchange(DOCUMENT_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue knowledgeDocumentQueue() {
        // 创建保存文档任务的持久化队列
        return QueueBuilder.durable(DOCUMENT_QUEUE).build();
    }

    @Bean
    public Binding knowledgeDocumentBinding(Queue knowledgeDocumentQueue,
                                             DirectExchange knowledgeDocumentExchange) {
        // 使用固定 RoutingKey 绑定队列和交换机
        return BindingBuilder.bind(knowledgeDocumentQueue)
                .to(knowledgeDocumentExchange)
                .with(DOCUMENT_INDEX_KEY);
    }
}
