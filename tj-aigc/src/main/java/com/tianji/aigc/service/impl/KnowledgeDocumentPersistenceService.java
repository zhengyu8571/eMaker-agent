package com.tianji.aigc.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tianji.aigc.dto.KnowledgeDocumentCreateDTO;
import com.tianji.aigc.entity.KnowledgeDocument;
import com.tianji.aigc.entity.KnowledgeTask;
import com.tianji.aigc.enums.KnowledgeDocumentStatus;
import com.tianji.aigc.enums.KnowledgeTaskStatus;
import com.tianji.aigc.enums.KnowledgeTaskType;
import com.tianji.aigc.event.KnowledgeDocumentTaskCreatedEvent;
import com.tianji.aigc.mapper.KnowledgeDocumentMapper;
import com.tianji.aigc.mapper.KnowledgeLibraryMapper;
import com.tianji.aigc.mapper.KnowledgeTaskMapper;
import com.tianji.aigc.vo.KnowledgeDocumentUploadVO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.DbException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentPersistenceService {

    // 新文档从第一个版本开始
    private static final int INITIAL_VERSION = 1;

    // 新任务默认最多重试三次
    private static final int DEFAULT_MAX_RETRIES = 3;

    // 查询知识库状态
    private final KnowledgeLibraryMapper libraryMapper;

    // 保存知识库文档记录
    private final KnowledgeDocumentMapper documentMapper;

    // 保存文档处理任务记录
    private final KnowledgeTaskMapper taskMapper;

    // 发布事务提交后需要处理的任务事件
    private final ApplicationEventPublisher eventPublisher;

    public void checkLibraryAvailable(Long libraryId) {
        // 根据知识库ID查询记录
        var library = this.libraryMapper.selectById(libraryId);
        // 不存在的知识库不能接收文档
        if (library == null) {
            throw new BadRequestException("知识库不存在");
        }
        // 已禁用的知识库不能接收文档
        if (!Objects.equals(library.getStatus(), 1)) {
            throw new BadRequestException("知识库已禁用");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentUploadVO createDocumentAndTask(KnowledgeDocumentCreateDTO dto) {
        // 写入前再次检查知识库状态
        checkLibraryAvailable(dto.getLibraryId());

        // 提前生成文档ID
        var documentId = IdWorker.getId();

        // 提前生成任务ID
        var taskId = IdWorker.getId();

        // 构建已上传的文档记录
        var document = KnowledgeDocument.builder()
                .id(documentId)
                .libraryId(dto.getLibraryId())
                .documentName(dto.getDocumentName())
                .originalFileName(dto.getOriginalFileName())
                .fileType(dto.getFileType())
                .contentType(dto.getContentType())
                .storageKey(dto.getStorageKey())
                .fileSize(dto.getFileSize())
                .fileHash(dto.getFileHash())
                .version(INITIAL_VERSION)
                .status(KnowledgeDocumentStatus.UPLOADED)
                .currentTaskId(taskId)
                .build();

        // 构建等待处理的索引任务
        var task = KnowledgeTask.builder()
                .id(taskId)
                .libraryId(dto.getLibraryId())
                .documentId(documentId)
                .documentVersion(INITIAL_VERSION)
                .sourceStorageKey(dto.getStorageKey())
                .taskType(KnowledgeTaskType.DOCUMENT_INDEX)
                .status(KnowledgeTaskStatus.PENDING)
                .retryCount(0)
                .maxRetries(DEFAULT_MAX_RETRIES)
                .totalChunks(0)
                .processedChunks(0)
                .failedChunks(0)
                .build();

        // 在同一事务内保存文档和任务
        if (this.documentMapper.insert(document) != 1 || this.taskMapper.insert(task) != 1) {
            throw new DbException("创建知识库文档任务失败");
        }

        // 发布事件并等待事务提交后投递RabbitMQ
        this.eventPublisher.publishEvent(new KnowledgeDocumentTaskCreatedEvent(taskId));

        // 返回本次上传创建的文档和任务信息
        return KnowledgeDocumentUploadVO.builder()
                .libraryId(dto.getLibraryId())
                .documentId(documentId)
                .taskId(taskId)
                .documentName(dto.getDocumentName())
                .originalFileName(dto.getOriginalFileName())
                .fileType(dto.getFileType())
                .fileSize(dto.getFileSize())
                .version(INITIAL_VERSION)
                .documentStatus(KnowledgeDocumentStatus.UPLOADED)
                .taskStatus(KnowledgeTaskStatus.PENDING)
                .build();
    }
}
