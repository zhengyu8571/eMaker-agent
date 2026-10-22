package com.tianji.aigc.service.impl;

import com.tianji.aigc.config.KnowledgeIndexingProperties;
import com.tianji.aigc.entity.KnowledgeTask;
import com.tianji.aigc.repository.KnowledgeBm25IndexRepository;
import com.tianji.aigc.service.KnowledgeDocumentIndexService;
import com.tianji.aigc.service.KnowledgeDocumentProcessingService;
import com.tianji.common.exceptions.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排任务抢占、文档处理、双索引写入和状态回写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentIndexServiceImpl implements KnowledgeDocumentIndexService {

    // 当前DashScope模型单次最多处理10段Embedding文本
    private static final int MAX_EMBEDDING_BATCH_SIZE = 10;

    // 下载原文件并完成解析、清洗和切块
    private final KnowledgeDocumentProcessingService processingService;

    // 写入 Redis 向量索引并触发 Embedding
    private final VectorStore vectorStore;

    // 写入 Elasticsearch BM25 索引
    private final KnowledgeBm25IndexRepository bm25IndexRepository;

    // 在短事务中维护任务和文档状态
    private final KnowledgeTaskLifecycleService lifecycleService;

    // 提供双索引批处理数量
    private final KnowledgeIndexingProperties indexingProperties;

    @Override
    public void index(Long taskId, boolean recoverProcessing) {
        // MQ 消息必须携带有效任务ID
        if (taskId == null) {
            throw new BadRequestException("任务ID不能为空");
        }

        // 使用数据库状态条件抢占任务
        var task = this.lifecycleService.start(taskId, recoverProcessing);
        if (task == null) {
            log.info("知识库任务无需重复处理，taskId={}", taskId);
            return;
        }

        var totalChunks = 0;
        var processedChunks = 0;
        var attemptedChunkIds = new ArrayList<String>();
        try {
            // 从 OSS 下载并生成带稳定ID的分块
            var chunks = this.processingService.prepareChunks(taskId);
            totalChunks = chunks.size();
            this.lifecycleService.recordTotalChunks(taskId, totalChunks);

            // 分批写入，避免一次请求超过 Embedding 服务限制
            var batchSize = Math.min(
                    MAX_EMBEDDING_BATCH_SIZE,
                    Math.max(1, this.indexingProperties.getBatchSize()));
            for (var offset = 0; offset < totalChunks; offset += batchSize) {
                var end = Math.min(offset + batchSize, totalChunks);
                var batch = List.copyOf(chunks.subList(offset, end));

                // 提前记录本批ID，任一索引失败时都能清理
                attemptedChunkIds.addAll(batch.stream().map(Document::getId).toList());

                // 先写 BM25 索引，再调用模型生成向量并写 Redis
                this.bm25IndexRepository.upsertAll(batch);
                this.vectorStore.add(batch);

                // 两个索引都成功后才累计处理进度
                processedChunks += batch.size();
                this.lifecycleService.addProcessedChunks(taskId, batch.size());
            }

            // 双索引全部成功后完成任务和文档状态
            this.lifecycleService.complete(task, totalChunks);
            log.info("知识库文档索引完成，taskId={}，chunkCount={}", taskId, totalChunks);
        } catch (RuntimeException e) {
            // 清理可能已经写入的分块，重试时再按稳定ID重建
            cleanupAttemptedChunks(attemptedChunkIds, e);

            // 保存错误摘要并决定进入 RETRY 还是 FAILED
            var status = this.lifecycleService.fail(task, totalChunks, 0, e);
            log.warn("知识库文档索引失败，taskId={}，processedChunks={}，status={}",
                    taskId, processedChunks, status, e);

            // 抛出异常交给 RabbitMQ 本地重试和错误队列处理
            throw e;
        }
    }

    private void cleanupAttemptedChunks(List<String> chunkIds, RuntimeException cause) {
        // 解析阶段失败时还没有任何索引数据
        if (chunkIds.isEmpty()) {
            return;
        }

        try {
            // 清理本次任务写入或尝试写入的 Redis 向量
            this.vectorStore.delete(chunkIds);
        } catch (RuntimeException cleanupException) {
            // 保留清理异常但不覆盖原始失败原因
            cause.addSuppressed(cleanupException);
            log.error("清理知识库Redis向量失败，chunkCount={}", chunkIds.size(), cleanupException);
        }

        try {
            // 清理本次任务写入或尝试写入的 ES 文档
            this.bm25IndexRepository.deleteAll(chunkIds);
        } catch (RuntimeException cleanupException) {
            // 保留清理异常但不覆盖原始失败原因
            cause.addSuppressed(cleanupException);
            log.error("清理知识库ES分块失败，chunkCount={}", chunkIds.size(), cleanupException);
        }
    }
}
