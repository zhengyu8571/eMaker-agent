package com.tianji.aigc.service.impl;

import com.tianji.aigc.dto.KnowledgeDocumentChunkContext;
import com.tianji.aigc.enums.KnowledgeTaskType;
import com.tianji.aigc.mapper.KnowledgeDocumentMapper;
import com.tianji.aigc.mapper.KnowledgeLibraryMapper;
import com.tianji.aigc.mapper.KnowledgeTaskMapper;
import com.tianji.aigc.service.FileStorage;
import com.tianji.aigc.service.KnowledgeDocumentProcessingService;
import com.tianji.aigc.support.document.DocumentParser;
import com.tianji.aigc.support.document.KnowledgeDocumentChunker;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 从任务快照下载、解析、清洗并切分知识库文档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentProcessingServiceImpl implements KnowledgeDocumentProcessingService {

    // 查询文档处理任务
    private final KnowledgeTaskMapper taskMapper;

    // 查询任务对应的文档
    private final KnowledgeDocumentMapper documentMapper;

    // 查询文档所属知识库
    private final KnowledgeLibraryMapper libraryMapper;

    // 从对象存储下载原始文件
    private final FileStorage fileStorage;

    // 解析并清洗原始文件
    private final DocumentParser documentParser;

    // 将正文转换成稳定文档分块
    private final KnowledgeDocumentChunker documentChunker;

    @Override
    public List<Document> prepareChunks(Long taskId) {
        // 任务ID不能为空
        if (taskId == null) {
            throw new BadRequestException("任务ID不能为空");
        }

        // 根据任务ID查询处理任务
        var task = this.taskMapper.selectById(taskId);

        // 不存在的任务无法继续处理
        if (task == null) {
            throw new CommonException("知识库任务不存在：" + taskId);
        }

        // 当前处理器只支持文档索引任务
        if (task.getTaskType() != KnowledgeTaskType.DOCUMENT_INDEX) {
            throw new CommonException("知识库任务类型不支持：" + task.getTaskType());
        }

        // 查询任务对应的文档记录
        var document = this.documentMapper.selectById(task.getDocumentId());

        // 文档记录不存在时停止处理
        if (document == null) {
            throw new CommonException("知识库文档不存在：" + task.getDocumentId());
        }

        // 检查任务是否仍对应文档当前版本
        if (!Objects.equals(document.getCurrentTaskId(), task.getId())
                || !Objects.equals(document.getVersion(), task.getDocumentVersion())) {
            throw new CommonException("任务已不是文档当前处理任务：" + taskId);
        }

        // 查询文档所属的知识库
        var library = this.libraryMapper.selectById(task.getLibraryId());

        // 知识库记录不存在时停止处理
        if (library == null) {
            throw new CommonException("知识库不存在：" + task.getLibraryId());
        }

        // 记录本次文档处理的任务和文档ID
        log.info("开始准备知识库文档分块，taskId={}，documentId={}", taskId, document.getId());

        // 使用任务快照中的存储地址下载原始文件
        try (var inputStream = this.fileStorage.downloadFile(task.getSourceStorageKey())) {
            // 解析并清洗下载的文档
            var content = this.documentParser.parseContent(
                    inputStream,
                    document.getOriginalFileName(),
                    document.getFileSize());

            // 构建每个分块需要携带的检索元数据
            var context = KnowledgeDocumentChunkContext.builder()
                    .libraryKey(library.getLibraryKey())
                    .libraryId(library.getId())
                    .documentId(document.getId())
                    .documentVersion(task.getDocumentVersion())
                    .documentTitle(document.getDocumentName())
                    .build();

            // 将清洗后的正文转换成 Spring AI 文档
            var chunks = this.documentChunker.split(context, content);

            // 没有正文的文档不能进入后续索引流程
            if (chunks.isEmpty()) {
                throw new CommonException("文档没有可处理的文本内容");
            }

            // 记录本次生成的文档分块数
            log.info("知识库文档分块准备完成，taskId={}，chunks={}", taskId, chunks.size());

            // 返回带稳定ID和元数据的文档分块
            return chunks;
        } catch (IOException e) {
            // 将关闭文件流时的异常转换成项目异常
            throw new CommonException("关闭知识库文件流失败", e);
        }
    }
}
