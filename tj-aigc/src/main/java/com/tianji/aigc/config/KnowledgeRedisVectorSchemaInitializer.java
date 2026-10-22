package com.tianji.aigc.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_CHUNK_ORDINAL;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_CONTENT_HASH;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_DOCUMENT_ID;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_DOCUMENT_TITLE;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_DOCUMENT_VERSION;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_LIBRARY_ID;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_LIBRARY_KEY;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_SECTION;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_TOKEN_COUNT;

/**
 * 启动时给已有Redis向量索引补充缺失的知识库元数据字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRedisVectorSchemaInitializer implements ApplicationRunner {

    // 使用包含知识库元数据声明的官方Redis VectorStore
    private final RedisVectorStore vectorStore;

    // 获取当前使用的Redis Search索引名称
    private final RedisVectorStoreProperties vectorStoreProperties;

    @Override
    public void run(ApplicationArguments args) {
        var indexName = this.vectorStoreProperties.getIndexName();
        var indexInfo = this.vectorStore.getJedis().ftInfo(indexName);
        var existingFields = new HashSet<String>();
        collectAttributeNames(indexInfo, existingFields);

        // 只添加缺失字段，不删除索引和已有向量数据
        var missingFields = metadataSchemaFields().entrySet().stream()
                .filter(entry -> !existingFields.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (missingFields.isEmpty()) {
            return;
        }

        this.vectorStore.getJedis().ftAlter(indexName, missingFields);
        log.info("补充Redis向量索引元数据字段完成，indexName={}，fieldCount={}",
                indexName, missingFields.size());
    }

    private Map<String, SchemaField> metadataSchemaFields() {
        // JSON中的元数据与正文、向量字段处于同一级
        var fields = new LinkedHashMap<String, SchemaField>();
        fields.put(METADATA_LIBRARY_KEY, tagField(METADATA_LIBRARY_KEY));
        fields.put(METADATA_LIBRARY_ID, numericField(METADATA_LIBRARY_ID));
        fields.put(METADATA_DOCUMENT_ID, numericField(METADATA_DOCUMENT_ID));
        fields.put(METADATA_DOCUMENT_VERSION, numericField(METADATA_DOCUMENT_VERSION));
        fields.put(METADATA_DOCUMENT_TITLE, textField(METADATA_DOCUMENT_TITLE));
        fields.put(METADATA_SECTION, textField(METADATA_SECTION));
        fields.put(METADATA_CHUNK_ORDINAL, numericField(METADATA_CHUNK_ORDINAL));
        fields.put(METADATA_TOKEN_COUNT, numericField(METADATA_TOKEN_COUNT));
        fields.put(METADATA_CONTENT_HASH, tagField(METADATA_CONTENT_HASH));
        return fields;
    }

    private SchemaField tagField(String name) {
        return TagField.of("$." + name).as(name);
    }

    private SchemaField numericField(String name) {
        return NumericField.of("$." + name).as(name);
    }

    private SchemaField textField(String name) {
        return TextField.of("$." + name).as(name);
    }

    private void collectAttributeNames(Object value, Set<String> target) {
        // Jedis不同版本可能将FT.INFO节点表示为Map或List
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                if ("attribute".equalsIgnoreCase(asText(key))) {
                    target.add(asText(child));
                }
                collectAttributeNames(child, target);
            });
            return;
        }

        if (!(value instanceof List<?> list)) {
            return;
        }

        for (var index = 0; index < list.size(); index++) {
            var item = list.get(index);
            if (index + 1 < list.size() && "attribute".equalsIgnoreCase(asText(item))) {
                target.add(asText(list.get(index + 1)));
            }
            collectAttributeNames(item, target);
        }
    }

    private String asText(Object value) {
        // RESP响应中的文本可能是String或byte数组
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value == null ? "" : value.toString();
    }
}
