package com.tianji.aigc.vo;

import com.tianji.aigc.enums.KnowledgeTaskStatus;
import com.tianji.aigc.enums.KnowledgeTaskType;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 展示知识库文档任务状态和处理进度。
 */
@Value
@Builder
public class KnowledgeTaskVO {

    // 文档处理任务ID
    Long taskId;

    // 任务所属知识库ID
    Long libraryId;

    // 任务处理的文档ID
    Long documentId;

    // 任务对应的文档版本
    Integer documentVersion;

    // 当前任务类型
    KnowledgeTaskType taskType;

    // 当前任务状态
    KnowledgeTaskStatus status;

    // 已安排的重试次数
    Integer retryCount;

    // 允许的最大重试次数
    Integer maxRetries;

    // 解析后得到的分块总数
    Integer totalChunks;

    // 已同时写入两个索引的分块数
    Integer processedChunks;

    // 本次处理失败的分块数
    Integer failedChunks;

    // 最后一次失败的异常类型
    String errorCode;

    // 最后一次失败的简短原因
    String errorMessage;

    // RabbitMQ 确认投递时间
    LocalDateTime sentAt;

    // 第一次开始处理时间
    LocalDateTime startedAt;

    // 最终处理结束时间
    LocalDateTime finishedAt;
}
