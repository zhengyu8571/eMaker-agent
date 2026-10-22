package com.tianji.aigc.mq;

import com.tianji.aigc.dto.KnowledgeDocumentTaskMessage;
import com.tianji.aigc.service.KnowledgeDocumentIndexService;
import com.tianji.common.exceptions.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import static com.tianji.aigc.constants.KnowledgeMqConstants.DOCUMENT_QUEUE;

/**
 * 接收 RabbitMQ 文档任务并启动索引流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentTaskConsumer {

    // 执行文档解析和双索引写入
    private final KnowledgeDocumentIndexService indexService;

    @RabbitListener(queues = DOCUMENT_QUEUE)
    public void consume(KnowledgeDocumentTaskMessage message,
                        @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) {
        // 空消息不能进入文档处理流程
        if (message == null || message.taskId() == null) {
            throw new BadRequestException("知识库文档任务消息缺少taskId");
        }

        log.info("收到知识库文档任务，taskId={}", message.taskId());

        // 异常继续向外抛出，由 RabbitMQ 重试机制接管
        this.indexService.index(message.taskId(), Boolean.TRUE.equals(redelivered));
    }
}
