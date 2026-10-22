package com.tianji.aigc.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;

/** PLAN 开启时延长同步模型调用的读取时间。 */
@Configuration
@ConditionalOnProperty(prefix = "tj.ai.planner", name = "enabled", havingValue = "true")
public class PlannerModelHttpConfig {

    // 读取 PLAN 专用超时配置
    private final PlannerProperties plannerProperties;

    public PlannerModelHttpConfig(PlannerProperties plannerProperties) {
        // 保存配置供 HTTP 客户端使用
        this.plannerProperties = plannerProperties;
    }

    @Bean
    public RestClientCustomizer plannerRestClientCustomizer() {
        // 沿用 OkHttp 请求工厂
        var factory = new OkHttp3ClientHttpRequestFactory();
        // 延长模型响应的读取等待时间
        factory.setReadTimeout(this.plannerProperties.getModelReadTimeout());
        // 为 Spring AI 使用的 RestClient 设置请求工厂
        return builder -> builder.requestFactory(factory);
    }
}
