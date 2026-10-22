package com.tianji.aigc.service.impl;

import com.tianji.aigc.mapper.KnowledgeTaskMapper;
import com.tianji.aigc.service.KnowledgeTaskService;
import com.tianji.aigc.vo.KnowledgeTaskVO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 从数据库读取知识库任务最终状态和当前进度。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeTaskServiceImpl implements KnowledgeTaskService {

    // 查询知识库文档任务
    private final KnowledgeTaskMapper taskMapper;

    @Override
    public KnowledgeTaskVO getById(Long taskId) {
        // 查询参数必须携带任务ID
        if (taskId == null) {
            throw new BadRequestException("任务ID不能为空");
        }

        // 根据主键读取任务最终状态
        var task = this.taskMapper.selectById(taskId);
        if (task == null) {
            throw new CommonException("知识库任务不存在：" + taskId);
        }

        // 只向前端返回任务状态，不暴露 OSS 地址和消息内部字段
        return KnowledgeTaskVO.builder()
                .taskId(task.getId())
                .libraryId(task.getLibraryId())
                .documentId(task.getDocumentId())
                .documentVersion(task.getDocumentVersion())
                .taskType(task.getTaskType())
                .status(task.getStatus())
                .retryCount(task.getRetryCount())
                .maxRetries(task.getMaxRetries())
                .totalChunks(task.getTotalChunks())
                .processedChunks(task.getProcessedChunks())
                .failedChunks(task.getFailedChunks())
                .errorCode(task.getErrorCode())
                .errorMessage(task.getErrorMessage())
                .sentAt(task.getSentAt())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .build();
    }
}
