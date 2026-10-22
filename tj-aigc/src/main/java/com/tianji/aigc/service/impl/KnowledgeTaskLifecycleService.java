package com.tianji.aigc.service.impl;

import com.tianji.aigc.entity.KnowledgeTask;
import com.tianji.aigc.enums.KnowledgeDocumentStatus;
import com.tianji.aigc.enums.KnowledgeTaskStatus;
import com.tianji.aigc.mapper.KnowledgeDocumentMapper;
import com.tianji.aigc.mapper.KnowledgeTaskMapper;
import com.tianji.common.exceptions.CommonException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 在短事务内维护文档任务状态，不包含 OSS 和索引调用。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeTaskLifecycleService {

    // 数据库错误信息允许保存的最大长度
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    // 数据库错误编码允许保存的最大长度
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    // 匹配指定范围内的控制字符
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    // 读写文档处理任务状态
    private final KnowledgeTaskMapper taskMapper;

    // 同步文档当前版本状态
    private final KnowledgeDocumentMapper documentMapper;

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeTask start(Long taskId, boolean recoverProcessing) {
        // 使用状态条件保证同一任务只能被一个消费者抢到
        var updated = this.taskMapper.tryMarkProcessing(
                taskId,
                KnowledgeTaskStatus.PENDING.getValue(),
                KnowledgeTaskStatus.RETRY.getValue(),
                KnowledgeTaskStatus.PROCESSING.getValue(),
                LocalDateTime.now());

        // Broker 重投消息时允许接管上次中断的处理中任务
        if (updated != 1 && recoverProcessing) {
            updated = this.taskMapper.reclaimProcessing(
                    taskId,
                    KnowledgeTaskStatus.PROCESSING.getValue());
        }

        // 没抢到说明其他消费者正在处理或任务已经结束
        if (updated != 1) {
            return null;
        }

        // 读取本次任务保存的文档版本快照
        var task = this.taskMapper.selectById(taskId);
        if (task == null) {
            throw new CommonException("知识库任务不存在：" + taskId);
        }

        // 只把仍指向本任务的文档版本改成处理中
        var documentUpdated = this.documentMapper.updateCurrentTaskStatus(
                task.getDocumentId(),
                task.getId(),
                task.getDocumentVersion(),
                KnowledgeDocumentStatus.PROCESSING.getValue());
        if (documentUpdated != 1) {
            throw new CommonException("知识库任务已不再对应文档当前版本：" + taskId);
        }
        return task;
    }

    public void recordTotalChunks(Long taskId, int totalChunks) {
        // 保存解析和切分后得到的分块总数
        requireSingleUpdate(
                this.taskMapper.recordTotalChunks(
                        taskId,
                        KnowledgeTaskStatus.PROCESSING.getValue(),
                        totalChunks),
                "记录知识库任务分块总数失败：" + taskId);
    }

    public void addProcessedChunks(Long taskId, int count) {
        // 只累计已经同时写入两个索引的分块
        requireSingleUpdate(
                this.taskMapper.addProcessedChunks(
                        taskId,
                        KnowledgeTaskStatus.PROCESSING.getValue(),
                        count),
                "更新知识库任务处理进度失败：" + taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(KnowledgeTask task, int totalChunks) {
        // 完成任务并写入最终分块数量
        requireSingleUpdate(
                this.taskMapper.markIndexed(
                        task.getId(),
                        KnowledgeTaskStatus.PROCESSING.getValue(),
                        KnowledgeTaskStatus.INDEXED.getValue(),
                        totalChunks,
                        LocalDateTime.now()),
                "完成知识库任务失败：" + task.getId());

        // 只完成仍指向本任务的文档版本
        requireSingleUpdate(
                this.documentMapper.updateCurrentTaskStatus(
                        task.getDocumentId(),
                        task.getId(),
                        task.getDocumentVersion(),
                        KnowledgeDocumentStatus.INDEXED.getValue()),
                "完成知识库文档索引失败：" + task.getDocumentId());
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeTaskStatus fail(KnowledgeTask task,
                                    int totalChunks,
                                    int processedChunks,
                                    Throwable cause) {
        // 计算本次尚未完成的分块数量
        var failedChunks = Math.max(0, totalChunks - processedChunks);

        // 错误编码只记录异常类型
        var errorCode = truncate(rootCause(cause).getClass().getSimpleName(), MAX_ERROR_CODE_LENGTH);

        // 错误信息去除控制字符并限制数据库长度
        var errorMessage = sanitizeErrorMessage(rootCause(cause).getMessage());

        // 未达到上限时增加重试次数并等待 RabbitMQ 本地重试
        var retryUpdated = this.taskMapper.markRetry(
                task.getId(),
                KnowledgeTaskStatus.PROCESSING.getValue(),
                KnowledgeTaskStatus.RETRY.getValue(),
                totalChunks,
                processedChunks,
                failedChunks,
                errorCode,
                errorMessage);
        if (retryUpdated == 1) {
            return KnowledgeTaskStatus.RETRY;
        }

        // 重试耗尽后记录最终失败状态
        requireSingleUpdate(
                this.taskMapper.markFailed(
                        task.getId(),
                        KnowledgeTaskStatus.PROCESSING.getValue(),
                        KnowledgeTaskStatus.FAILED.getValue(),
                        totalChunks,
                        processedChunks,
                        failedChunks,
                        errorCode,
                        errorMessage,
                        LocalDateTime.now()),
                "记录知识库任务失败状态失败：" + task.getId());

        // 旧版本任务失败时不能覆盖新版本文档状态
        this.documentMapper.updateCurrentTaskStatus(
                task.getDocumentId(),
                task.getId(),
                task.getDocumentVersion(),
                KnowledgeDocumentStatus.FAILED.getValue());
        return KnowledgeTaskStatus.FAILED;
    }

    private void requireSingleUpdate(int updated, String message) {
        // 状态条件不匹配时停止当前处理
        if (updated != 1) {
            throw new CommonException(message);
        }
    }

    private Throwable rootCause(Throwable cause) {
        // 沿异常链查找最底层原因
        var current = cause;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String sanitizeErrorMessage(String message) {
        // 没有异常信息时使用固定说明
        if (message == null || message.isBlank()) {
            return "知识库文档处理失败";
        }

        // 去除控制字符并压缩空白
        var sanitized = CONTROL_CHARS.matcher(message).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
        return truncate(sanitized, MAX_ERROR_MESSAGE_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        // 字符串超过数据库列长度时截断
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
