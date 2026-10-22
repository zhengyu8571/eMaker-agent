package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 配置单次Agent运行的默认资源边界。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.runtime")
public class AgentRuntimeProperties {

    // 控制统一运行时是否接管DIRECT聊天链
    private boolean enabled = true;

    // 限制单次运行的最大执行步骤数
    private int maxSteps = 20;

    // 限制单次运行的最大模型调用次数
    private int maxModelCalls = 8;

    // 限制同一请求最多重规划一次
    private int maxReplans = 1;

    // 限制单次运行的最大工具调用次数
    private int maxToolCalls = 12;

    // 限制单次运行的Token估算总量
    private int maxTokens = 32000;

    // 限制单次运行允许持续的总时间
    private Duration timeout = Duration.ofMinutes(2);

    // 设置能力执行线程池的核心线程数
    private int corePoolSize = 4;

    // 设置能力执行线程池的最大线程数
    private int maxPoolSize = 16;

    // 设置能力执行线程池的等待队列容量
    private int queueCapacity = 100;
}
