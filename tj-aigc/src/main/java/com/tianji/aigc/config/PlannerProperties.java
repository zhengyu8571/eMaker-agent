package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** 控制阶段七 PLAN 主链是否接管复杂请求。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.planner")
public class PlannerProperties {

    // 默认关闭，显式开启后才允许路由执行 PLAN
    private boolean enabled;

    // PLAN 模型生成较长时允许等待完整响应
    private Duration modelReadTimeout = Duration.ofSeconds(120);

    // 包含排队、SDK重试与解析的单次模型等待上限
    private Duration modelCallTimeout = Duration.ofSeconds(30);
}
