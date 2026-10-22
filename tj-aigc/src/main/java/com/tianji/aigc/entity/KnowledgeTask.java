package com.tianji.aigc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tianji.aigc.enums.KnowledgeTaskStatus;
import com.tianji.aigc.enums.KnowledgeTaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档异步处理任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_task")
public class KnowledgeTask implements Serializable {

    // 任务主键
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // 任务所属知识库ID
    private Long libraryId;

    // 任务处理的文档ID
    private Long documentId;

    // 任务处理的文档版本
    private Integer documentVersion;

    /**
     * 创建任务时保存的源文件快照，避免文档升级后旧任务读取到新版本文件。
     */
    private String sourceStorageKey;

    // 任务执行类型
    private KnowledgeTaskType taskType;

    // 任务当前状态
    private KnowledgeTaskStatus status;

    // 已执行的重试次数
    private Integer retryCount;

    // 允许执行的最大重试次数
    private Integer maxRetries;

    // 发送到消息队列的消息ID
    private String messageId;

    // 本次任务生成的分块总数
    private Integer totalChunks;

    // 已成功处理的分块数
    private Integer processedChunks;

    // 处理失败的分块数
    private Integer failedChunks;

    // 最后一次失败的错误编码
    private String errorCode;

    // 最后一次失败的错误信息
    private String errorMessage;

    // 消息成功投递的时间
    private LocalDateTime sentAt;

    // 任务开始处理的时间
    private LocalDateTime startedAt;

    // 任务结束处理的时间
    private LocalDateTime finishedAt;

    // 记录创建时间
    private LocalDateTime createTime;

    // 记录最后更新时间
    private LocalDateTime updateTime;

    // 创建人ID
    @TableField(fill = FieldFill.INSERT)
    private Long creater;

    // 最后修改人ID
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updater;
}
