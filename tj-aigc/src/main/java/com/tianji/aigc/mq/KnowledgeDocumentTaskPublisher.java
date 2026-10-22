package com.tianji.aigc.mq;

import com.tianji.aigc.config.KnowledgeMessagingProperties;
import com.tianji.aigc.dto.KnowledgeDocumentTaskMessage;
import com.tianji.aigc.enums.KnowledgeTaskStatus;
import com.tianji.aigc.mapper.KnowledgeTaskMapper;
import com.tianji.common.exceptions.CommonException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.tianji.aigc.constants.KnowledgeMqConstants.DOCUMENT_EXCHANGE;
import static com.tianji.aigc.constants.KnowledgeMqConstants.DOCUMENT_INDEX_KEY;

/**
 * 发送知识库文档处理任务并等待 RabbitMQ 确认。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentTaskPublisher {

    // 发送消息并接收 Broker 确认
    private final RabbitTemplate rabbitTemplate;

    // 记录任务是否已经确认投递
    private final KnowledgeTaskMapper taskMapper;

    // 提供 Broker 确认等待时间
    private final KnowledgeMessagingProperties messagingProperties;

    public void publish(Long taskId) {
        // 查询需要投递的任务
        var task = this.taskMapper.selectById(taskId);

        // 不存在的任务不能发送消息
        if (task == null) {
            throw new CommonException("知识库任务不存在：" + taskId);
        }

        // 已经记录投递时间的任务不重复发送
        if (task.getSentAt() != null) {
            return;
        }

        // 只有待处理任务允许首次投递
        if (task.getStatus() != KnowledgeTaskStatus.PENDING) {
            throw new CommonException("知识库任务当前不能投递：" + task.getStatus());
        }

        // 为本次 RabbitMQ 消息生成唯一标识
        var messageId = UUID.randomUUID().toString();

        // 使用消息标识关联 Broker 确认结果
        var correlationData = new CorrelationData(messageId);

        // 构建只包含任务ID的消息体并发送
        this.rabbitTemplate.convertAndSend(
                DOCUMENT_EXCHANGE,
                DOCUMENT_INDEX_KEY,
                new KnowledgeDocumentTaskMessage(taskId),
                message -> {
                    // 将消息标识写入 RabbitMQ 消息属性
                    message.getMessageProperties().setMessageId(messageId);

                    // 将消息标记为持久化消息
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);

                    return message;
                },
                correlationData);

        // 等待 Broker 确认并检查路由结果
        waitForConfirmation(taskId, correlationData);

        // Broker确认后记录消息标识和投递时间
        recordConfirmedDispatch(taskId, messageId);
    }

    private void waitForConfirmation(Long taskId, CorrelationData correlationData) {
        try {
            // 在配置的最长时间内等待 Broker 确认
            var confirm = correlationData.getFuture().get(
                    this.messagingProperties.getConfirmTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);

            // Broker拒绝消息时停止记录投递成功
            if (!confirm.isAck()) {
                throw new CommonException("RabbitMQ拒绝知识库任务：" + taskId + "，原因：" + confirm.getReason());
            }

            // 消息无法路由到队列时停止记录投递成功
            if (correlationData.getReturned() != null) {
                throw new CommonException("RabbitMQ无法路由知识库任务：" + taskId);
            }
        } catch (InterruptedException e) {
            // 恢复线程中断标记
            Thread.currentThread().interrupt();

            throw new CommonException("等待RabbitMQ确认时线程被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            // 将确认异常和超时统一转换成项目异常
            throw new CommonException("等待RabbitMQ确认知识库任务失败：" + taskId, e);
        }
    }

    private void recordConfirmedDispatch(Long taskId, String messageId) {
        // 只为尚未投递的待处理任务记录确认信息
        var updated = this.taskMapper.markDispatched(
                taskId,
                KnowledgeTaskStatus.PENDING.getValue(),
                messageId,
                LocalDateTime.now());

        // 未更新记录时保留任务等待后续校验
        if (updated != 1) {
            throw new CommonException("RabbitMQ已确认，但任务投递信息更新失败：" + taskId);
        }
    }
}
