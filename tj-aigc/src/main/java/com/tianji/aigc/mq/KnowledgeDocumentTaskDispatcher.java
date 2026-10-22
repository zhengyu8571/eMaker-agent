package com.tianji.aigc.mq;

import com.tianji.aigc.config.KnowledgeMessagingProperties;
import com.tianji.aigc.enums.KnowledgeTaskStatus;
import com.tianji.aigc.mapper.KnowledgeTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 重新投递尚未获得 RabbitMQ 确认的文档任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentTaskDispatcher {

    // 查询尚未确认投递的任务
    private final KnowledgeTaskMapper taskMapper;

    // 发送任务并等待 RabbitMQ 确认
    private final KnowledgeDocumentTaskPublisher taskPublisher;

    // 提供单次扫描的任务数量
    private final KnowledgeMessagingProperties messagingProperties;

    @Scheduled(fixedDelayString = "${tj.ai.knowledge.messaging.retry-fixed-delay:30000}")
    public void retryPendingTasks() {
        // 至少允许单次扫描一个任务
        var batchSize = Math.max(1, this.messagingProperties.getRetryBatchSize());

        // 查询尚未记录投递时间的待处理任务
        var taskIds = this.taskMapper.selectPendingDispatchTaskIds(
                KnowledgeTaskStatus.PENDING.getValue(),
                batchSize);

        // 逐个重新投递待处理任务
        for (var taskId : taskIds) {
            try {
                // 发送任务并等待 Broker 确认
                this.taskPublisher.publish(taskId);
            } catch (RuntimeException e) {
                // 单个任务失败时继续处理本批次其他任务
                log.error("重新投递知识库文档任务失败，taskId={}", taskId, e);
            }
        }
    }
}
