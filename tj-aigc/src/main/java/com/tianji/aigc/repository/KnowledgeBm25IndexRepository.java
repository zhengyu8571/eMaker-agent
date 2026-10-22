package com.tianji.aigc.repository;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 读写知识库分块的 BM25 索引。
 */
public interface KnowledgeBm25IndexRepository {

    /**
     * 使用稳定 chunkId 新增或覆盖分块。
     */
    void upsertAll(List<Document> chunks);

    /**
     * 根据稳定 chunkId 批量删除分块。
     */
    void deleteAll(List<String> chunkIds);
}
