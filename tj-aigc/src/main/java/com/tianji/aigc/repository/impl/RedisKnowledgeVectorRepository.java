package com.tianji.aigc.repository.impl;

import com.tianji.aigc.dto.RetrievalScore;
import com.tianji.aigc.dto.RetrievedChunk;
import com.tianji.aigc.enums.RetrievalSource;
import com.tianji.aigc.repository.KnowledgeVectorRepository;
import com.tianji.aigc.support.document.KnowledgeDocumentChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用Spring AI Redis VectorStore完成知识库向量召回。
 */
@Repository
@RequiredArgsConstructor
public class RedisKnowledgeVectorRepository implements KnowledgeVectorRepository {

    // Spring AI官方向量库抽象
    private final VectorStore vectorStore;

    @Override
    public List<RetrievedChunk> search(String query,
                                       Set<String> libraryKeys,
                                       int topK,
                                       double similarityThreshold) {
        var normalizedKeys = normalizeLibraryKeys(libraryKeys);
        var filterExpression = buildLibraryFilter(normalizedKeys);

        // SearchRequest和过滤表达式均来自Spring AI官方API
        var searchRequest = SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, topK))
                .similarityThreshold(normalizeThreshold(similarityThreshold))
                .filterExpression(filterExpression)
                .build();
        var documents = this.vectorStore.similaritySearch(searchRequest);

        // 保留VectorStore返回顺序，并记录从1开始的向量排名
        for (var index = 0; index < documents.size(); index++) {
            var document = documents.get(index);
            if (!normalizedKeys.contains(asString(document.getMetadata()
                    .get(KnowledgeDocumentChunker.METADATA_LIBRARY_KEY)))) {
                throw new IllegalStateException("向量召回结果超出指定知识库范围");
            }
        }

        return java.util.stream.IntStream.range(0, documents.size())
                .mapToObj(index -> toRetrievedChunk(documents.get(index), index + 1))
                .toList();
    }

    private Set<String> normalizeLibraryKeys(Set<String> libraryKeys) {
        // 去除空知识库标识并保持调用方传入顺序
        var normalizedKeys = new LinkedHashSet<String>();
        if (libraryKeys != null) {
            libraryKeys.stream()
                    .filter(key -> key != null && !key.isBlank())
                    .map(String::strip)
                    .forEach(normalizedKeys::add);
        }
        if (normalizedKeys.isEmpty()) {
            throw new IllegalArgumentException("向量召回必须指定知识库范围");
        }
        return Set.copyOf(normalizedKeys);
    }

    private Filter.Expression buildLibraryFilter(Set<String> libraryKeys) {
        var builder = new FilterExpressionBuilder();
        if (libraryKeys.size() == 1) {
            return builder.eq(
                    KnowledgeDocumentChunker.METADATA_LIBRARY_KEY,
                    libraryKeys.iterator().next()).build();
        }

        // 多知识库使用官方IN表达式一次过滤
        var values = libraryKeys.stream().map(value -> (Object) value).toList();
        return builder.in(KnowledgeDocumentChunker.METADATA_LIBRARY_KEY, values).build();
    }

    private double normalizeThreshold(double threshold) {
        // Spring AI要求相似度阈值位于0到1之间
        if (!Double.isFinite(threshold)) {
            return 0.6d;
        }
        return Math.max(0d, Math.min(1d, threshold));
    }

    private RetrievedChunk toRetrievedChunk(Document document, int rank) {
        var metadata = document.getMetadata();
        var score = document.getScore() == null ? 0d : document.getScore();

        // 将Spring AI Document转换成项目内部统一Chunk
        return new RetrievedChunk(
                document.getId(),
                document.getText(),
                asString(metadata.get(KnowledgeDocumentChunker.METADATA_LIBRARY_KEY)),
                // 优先读取不丢精度的字符串副本
                metadataId(metadata, KnowledgeDocumentChunker.METADATA_LIBRARY_ID_EXACT, KnowledgeDocumentChunker.METADATA_LIBRARY_ID),
                // 旧数据没有副本时仍检查数值是否可信
                metadataId(metadata, KnowledgeDocumentChunker.METADATA_DOCUMENT_ID_EXACT, KnowledgeDocumentChunker.METADATA_DOCUMENT_ID),
                asInteger(metadata.get(KnowledgeDocumentChunker.METADATA_DOCUMENT_VERSION)),
                asString(metadata.get(KnowledgeDocumentChunker.METADATA_DOCUMENT_TITLE)),
                asString(metadata.get(KnowledgeDocumentChunker.METADATA_SECTION)),
                asInteger(metadata.get(KnowledgeDocumentChunker.METADATA_CHUNK_ORDINAL)),
                asInteger(metadata.get(KnowledgeDocumentChunker.METADATA_TOKEN_COUNT)),
                Map.of(RetrievalSource.VECTOR, new RetrievalScore(rank, score)),
                null,
                null);
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Long metadataId(Map<String, Object> metadata, String exactKey, String legacyKey) {
        // 精确副本由写入端保存为字符串
        var exact = metadata.get(exactKey);
        // 精确副本不经过NUMERIC索引取值
        if (exact != null) {
            // 不对异常副本静默回退
            return Long.valueOf(exact.toString());
        }
        // SDK也可能把NUMERIC结果返回为已舍入的字符串
        var legacy = metadata.get(legacyKey);
        // 超出安全范围的旧字符串不能证明原ID
        if (legacy instanceof String text && new java.math.BigDecimal(text).abs().compareTo(java.math.BigDecimal.valueOf(9_007_199_254_740_992L)) >= 0) {
            // 提示先迁移旧数据，而不是返回错误来源
            throw new IllegalStateException("向量元数据ID精度不可信，请补齐精确ID字段");
        }
        // 小整数和原生整数保留兼容
        return asLong(legacy);
    }

    private Long asLong(Object value) {
        // 浮点大整数无法可靠还原，不能返回错误来源ID
        if (value instanceof Double || value instanceof Float) {
            // 只兼容能够精确表示的整数
            var number = ((Number) value).doubleValue();
            // 超出精确范围或含小数时拒绝伪造ID
            if (!Double.isFinite(number) || Math.abs(number) >= 9_007_199_254_740_992d || number != Math.rint(number)) {
                // 由召回层降级，旧数据需补齐精确字段
                throw new IllegalStateException("向量元数据ID精度不可信，请补齐精确ID字段");
            }
        }
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
}
