package com.tianji.aigc.mq;

import com.tianji.aigc.event.KnowledgeDocumentTaskCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 数据库事务提交后投递新建的文档任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentTaskCreatedListener {

    // 向 RabbitMQ 发送文档处理任务
    private final KnowledgeDocumentTaskPublisher taskPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(KnowledgeDocumentTaskCreatedEvent event) {
        try {
            // 事务提交后发送文档任务
            this.taskPublisher.publish(event.taskId());
        } catch (RuntimeException e) {
            // 投递失败时保留数据库中的 PENDING 任务
            log.error("投递知识库文档任务失败，taskId={}", event.taskId(), e);
        }
    }
}
