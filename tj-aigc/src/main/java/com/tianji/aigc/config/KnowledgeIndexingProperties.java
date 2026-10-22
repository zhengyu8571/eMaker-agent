package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库分块索引配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.knowledge.indexing")
public class KnowledgeIndexingProperties {

    // 单次写入向量库和 ES 的最大分块数
    private int batchSize = 10;

    // 保存知识库分块的 ES 索引名称
    private String bm25IndexName = "aigc_knowledge_chunk";
}
