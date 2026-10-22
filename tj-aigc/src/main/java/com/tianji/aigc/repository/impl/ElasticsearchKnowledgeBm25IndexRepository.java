package com.tianji.aigc.repository.impl;

import com.tianji.aigc.config.KnowledgeIndexingProperties;
import com.tianji.aigc.repository.KnowledgeBm25IndexRepository;
import com.tianji.aigc.support.document.KnowledgeDocumentChunker;
import com.tianji.common.exceptions.CommonException;
import lombok.RequiredArgsConstructor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 使用 Elasticsearch 7 保存可供 BM25 检索的知识库分块。
 */
@Repository
@RequiredArgsConstructor
public class ElasticsearchKnowledgeBm25IndexRepository implements KnowledgeBm25IndexRepository {

    // 创建索引时使用的字段映射
    private static final String INDEX_MAPPING = """
            {
              "properties": {
                "chunk_id": {"type": "keyword"},
                "content": {"type": "text"},
                "kb_key": {"type": "keyword"},
                "kb_id": {"type": "long"},
                "doc_id": {"type": "long"},
                "document_version": {"type": "integer"},
                "document_title": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                "section": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                "chunk_ordinal": {"type": "integer"},
                "token_count": {"type": "integer"},
                "content_hash": {"type": "keyword"},
                "indexed_at": {"type": "date"}
              }
            }
            """;

    // 调用 Elasticsearch 7 REST API
    private final RestHighLevelClient restHighLevelClient;

    // 提供知识库 BM25 索引名称
    private final KnowledgeIndexingProperties indexingProperties;

    // 避免每批分块都重复查询索引是否存在
    private volatile boolean indexReady;

    @Override
    public void upsertAll(List<Document> chunks) {
        // 空批次不访问 Elasticsearch
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        ensureIndexExists();

        // 同一个 chunkId 再次写入时由 ES 覆盖旧文档
        var request = new BulkRequest(this.indexingProperties.getBm25IndexName());
        for (var chunk : chunks) {
            request.add(new IndexRequest(this.indexingProperties.getBm25IndexName())
                    .id(chunk.getId())
                    .source(toSource(chunk)));
        }

        try {
            // 批量写入当前分块
            var response = this.restHighLevelClient.bulk(request, RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                throw new CommonException("知识库分块写入ES失败：" + response.buildFailureMessage());
            }
        } catch (IOException e) {
            throw new CommonException("知识库分块写入ES失败", e);
        }
    }

    @Override
    public void deleteAll(List<String> chunkIds) {
        // 没有已尝试的分块时无需清理
        if (chunkIds == null || chunkIds.isEmpty() || !this.indexReady) {
            return;
        }

        // 使用稳定 chunkId 批量清理本次任务数据
        var request = new BulkRequest(this.indexingProperties.getBm25IndexName());
        for (var chunkId : chunkIds) {
            request.add(new DeleteRequest(this.indexingProperties.getBm25IndexName(), chunkId));
        }

        try {
            // 删除不存在的 chunkId 也视为幂等成功
            var response = this.restHighLevelClient.bulk(request, RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                throw new CommonException("清理知识库ES分块失败：" + response.buildFailureMessage());
            }
        } catch (IOException e) {
            throw new CommonException("清理知识库ES分块失败", e);
        }
    }

    private synchronized void ensureIndexExists() {
        // 当前进程已经确认索引存在时直接返回
        if (this.indexReady) {
            return;
        }

        var indexName = this.indexingProperties.getBm25IndexName();
        try {
            // 索引不存在时使用固定 mapping 创建
            if (!this.restHighLevelClient.indices().exists(
                    new GetIndexRequest(indexName), RequestOptions.DEFAULT)) {
                var request = new CreateIndexRequest(indexName)
                        .mapping(INDEX_MAPPING, XContentType.JSON);
                this.restHighLevelClient.indices().create(request, RequestOptions.DEFAULT);
            }
            this.indexReady = true;
        } catch (IOException e) {
            throw new CommonException("初始化知识库ES索引失败", e);
        }
    }

    private LinkedHashMap<String, Object> toSource(Document chunk) {
        // 保留切块阶段生成的全部检索元数据
        var source = new LinkedHashMap<String, Object>(chunk.getMetadata());

        // 写入 BM25 检索需要的稳定ID和正文
        source.put("chunk_id", chunk.getId());
        source.put("content", chunk.getText());
        source.put("indexed_at", Instant.now().toString());

        // 确保核心元数据沿用切块组件定义的字段名
        requireMetadata(source, KnowledgeDocumentChunker.METADATA_LIBRARY_KEY);
        requireMetadata(source, KnowledgeDocumentChunker.METADATA_DOCUMENT_ID);
        requireMetadata(source, KnowledgeDocumentChunker.METADATA_DOCUMENT_VERSION);
        return source;
    }

    private void requireMetadata(LinkedHashMap<String, Object> source, String key) {
        // 缺少核心元数据的分块不能进入检索索引
        if (!source.containsKey(key) || source.get(key) == null) {
            throw new CommonException("知识库分块缺少元数据：" + key);
        }
    }
}
