package com.tianji.aigc.repository.impl;

import com.tianji.aigc.config.KnowledgeIndexingProperties;
import com.tianji.aigc.dto.RetrievalScore;
import com.tianji.aigc.dto.RetrievedChunk;
import com.tianji.aigc.enums.RetrievalSource;
import com.tianji.aigc.repository.KnowledgeBm25Repository;
import com.tianji.aigc.support.document.KnowledgeDocumentChunker;
import com.tianji.common.exceptions.CommonException;
import lombok.RequiredArgsConstructor;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 使用 Elasticsearch 7 官方客户端完成知识库 BM25 召回。
 */
@Repository
@RequiredArgsConstructor
public class ElasticsearchKnowledgeBm25Repository implements KnowledgeBm25Repository {

    // 调用 Elasticsearch 7 官方 REST API
    private final RestHighLevelClient restHighLevelClient;

    // 读取阶段二创建的知识库索引名称
    private final KnowledgeIndexingProperties indexingProperties;

    @Override
    public List<RetrievedChunk> search(String query,
                                       Set<String> libraryKeys,
                                       int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("BM25召回问题不能为空");
        }
        var normalizedKeys = normalizeLibraryKeys(libraryKeys);

        // 同时匹配正文、标题和章节，避免按文档标识提问时只召回首页
        var boolQuery = QueryBuilders.boolQuery()
                .must(QueryBuilders.multiMatchQuery(query.strip()).field("content").field("document_title", 2f).field("section", 1.5f))
                .filter(QueryBuilders.termsQuery(
                        KnowledgeDocumentChunker.METADATA_LIBRARY_KEY,
                        normalizedKeys));
        var searchSource = new SearchSourceBuilder()
                .query(boolQuery)
                .size(Math.max(1, topK));
        var request = new SearchRequest(this.indexingProperties.getBm25IndexName())
                .source(searchSource);

        try {
            // ES 默认按 _score 从高到低返回命中结果
            var hits = this.restHighLevelClient.search(request, RequestOptions.DEFAULT)
                    .getHits()
                    .getHits();

            // 保留 ES 排名，并将原始 BM25 分数写入统一结果
            return IntStream.range(0, hits.length)
                    .mapToObj(index -> toRetrievedChunk(
                            hits[index], index + 1, normalizedKeys))
                    .toList();
        } catch (IOException e) {
            throw new CommonException("知识库BM25召回失败", e);
        }
    }

    private Set<String> normalizeLibraryKeys(Set<String> libraryKeys) {
        // 去除空知识库标识，禁止无范围检索全部知识库
        var normalizedKeys = new LinkedHashSet<String>();
        if (libraryKeys != null) {
            libraryKeys.stream()
                    .filter(key -> key != null && !key.isBlank())
                    .map(String::strip)
                    .forEach(normalizedKeys::add);
        }
        if (normalizedKeys.isEmpty()) {
            throw new IllegalArgumentException("BM25召回必须指定知识库范围");
        }
        return Set.copyOf(normalizedKeys);
    }

    private RetrievedChunk toRetrievedChunk(SearchHit hit,
                                              int rank,
                                              Set<String> libraryKeys) {
        var source = hit.getSourceAsMap();
        var libraryKey = asString(source.get(
                KnowledgeDocumentChunker.METADATA_LIBRARY_KEY));
        if (!libraryKeys.contains(libraryKey)) {
            throw new IllegalStateException("BM25召回结果超出指定知识库范围");
        }

        // 将 ES 文档转换成项目内部统一 Chunk
        return new RetrievedChunk(
                valueOrDefault(source.get("chunk_id"), hit.getId()),
                asString(source.get("content")),
                libraryKey,
                asLong(source.get(KnowledgeDocumentChunker.METADATA_LIBRARY_ID)),
                asLong(source.get(KnowledgeDocumentChunker.METADATA_DOCUMENT_ID)),
                asInteger(source.get(KnowledgeDocumentChunker.METADATA_DOCUMENT_VERSION)),
                asString(source.get(KnowledgeDocumentChunker.METADATA_DOCUMENT_TITLE)),
                asString(source.get(KnowledgeDocumentChunker.METADATA_SECTION)),
                asInteger(source.get(KnowledgeDocumentChunker.METADATA_CHUNK_ORDINAL)),
                asInteger(source.get(KnowledgeDocumentChunker.METADATA_TOKEN_COUNT)),
                Map.of(RetrievalSource.BM25,
                        new RetrievalScore(rank, normalizeScore(hit.getScore()))),
                null,
                null);
    }

    private String valueOrDefault(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(value.toString());
    }

    private double normalizeScore(float score) {
        return Float.isFinite(score) ? score : 0d;
    }
}
