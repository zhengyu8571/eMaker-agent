package com.tianji.aigc.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_CHUNK_ORDINAL;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_CONTENT_HASH;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_DOCUMENT_ID;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_DOCUMENT_ID_EXACT;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_DOCUMENT_TITLE;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_DOCUMENT_VERSION;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_LIBRARY_ID;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_LIBRARY_ID_EXACT;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_LIBRARY_KEY;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_SECTION;
import static com.tianji.aigc.support.document.KnowledgeDocumentChunker.METADATA_TOKEN_COUNT;

/**
 * 为Spring AI Redis VectorStore补充知识库过滤和来源字段。
 */
@Configuration
@EnableConfigurationProperties(RedisVectorStoreProperties.class)
public class KnowledgeRedisVectorStoreConfig {

    @Bean
    public RedisVectorStore vectorStore(
            EmbeddingModel embeddingModel,
            RedisVectorStoreProperties properties,
            JedisConnectionFactory connectionFactory,
            BatchingStrategy batchingStrategy,
            ObjectProvider<ObservationRegistry> observationRegistry) {

        // 沿用Spring Data Redis连接信息创建Spring AI需要的Jedis客户端
        var clientConfig = DefaultJedisClientConfig.builder()
                .ssl(connectionFactory.isUseSsl())
                .clientName(connectionFactory.getClientName())
                .timeoutMillis(connectionFactory.getTimeout())
                .password(connectionFactory.getPassword())
                .database(connectionFactory.getDatabase())
                .build();
        var jedis = new JedisPooled(
                new HostAndPort(connectionFactory.getHostName(), connectionFactory.getPort()),
                clientConfig);

        // 在官方VectorStore上声明检索需要返回和过滤的元数据
        return RedisVectorStore.builder(jedis, embeddingModel)
                .initializeSchema(properties.isInitializeSchema())
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                .metadataFields(
                        RedisVectorStore.MetadataField.tag(METADATA_LIBRARY_KEY),
                        RedisVectorStore.MetadataField.numeric(METADATA_LIBRARY_ID),
                        RedisVectorStore.MetadataField.numeric(METADATA_DOCUMENT_ID),
                        // 官方SDK只取回已声明的字段，精确副本必须显式加入
                        RedisVectorStore.MetadataField.tag(METADATA_DOCUMENT_ID_EXACT),
                        // 不修改原NUMERIC字段，兼容已有索引
                        RedisVectorStore.MetadataField.tag(METADATA_LIBRARY_ID_EXACT),
                        RedisVectorStore.MetadataField.numeric(METADATA_DOCUMENT_VERSION),
                        RedisVectorStore.MetadataField.text(METADATA_DOCUMENT_TITLE),
                        RedisVectorStore.MetadataField.text(METADATA_SECTION),
                        RedisVectorStore.MetadataField.numeric(METADATA_CHUNK_ORDINAL),
                        RedisVectorStore.MetadataField.numeric(METADATA_TOKEN_COUNT),
                        RedisVectorStore.MetadataField.tag(METADATA_CONTENT_HASH))
                .batchingStrategy(batchingStrategy)
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
    }
}
