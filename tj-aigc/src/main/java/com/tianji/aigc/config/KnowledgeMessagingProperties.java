package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 知识库任务消息配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.knowledge.messaging")
public class KnowledgeMessagingProperties {

    // 等待 RabbitMQ 确认的最长时间
    private Duration confirmTimeout = Duration.ofSeconds(5);

    // 单次重新投递读取的最大任务数
    private int retryBatchSize = 50;
}
