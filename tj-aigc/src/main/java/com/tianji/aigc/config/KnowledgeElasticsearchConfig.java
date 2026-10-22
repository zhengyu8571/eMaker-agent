package com.tianji.aigc.config;

import cn.hutool.core.collection.CollUtil;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 创建知识库 BM25 索引使用的 Elasticsearch 客户端。
 */
@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class KnowledgeElasticsearchConfig {

    @Bean
    public RestHighLevelClient knowledgeRestHighLevelClient(
            ElasticsearchProperties elasticsearchProperties) {
        // 当前项目只连接配置中的第一个 ES 地址
        var uri = CollUtil.getFirst(elasticsearchProperties.getUris());
        return new RestHighLevelClient(RestClient.builder(HttpHost.create(uri)));
    }
}
