package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.routing")
public class RoutingProperties {

    /**
     * 低于此置信度时不进入业务智能体，要求用户补充问题。
     */
    private double minConfidence = 0.65d;
}
