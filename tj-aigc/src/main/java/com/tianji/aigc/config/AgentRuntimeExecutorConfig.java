package com.tianji.aigc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建能力调用使用的独立有界线程池。
 */
@Configuration
public class AgentRuntimeExecutorConfig {

    @Bean(name = "agentCapabilityExecutor", destroyMethod = "shutdown")
    public ExecutorService agentCapabilityExecutor(AgentRuntimeProperties properties) {
        // 确保核心线程数至少为一
        var corePoolSize = Math.max(1, properties.getCorePoolSize());
        // 确保最大线程数不小于核心线程数
        var maxPoolSize = Math.max(corePoolSize, properties.getMaxPoolSize());
        // 确保等待队列至少可以保存一个任务
        var queueCapacity = Math.max(1, properties.getQueueCapacity());
        // 生成便于日志定位的线程序号
        var sequence = new AtomicInteger();
        // 创建拒绝无限堆积任务的能力执行线程池
        return new ThreadPoolExecutor(corePoolSize, maxPoolSize, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity), task -> {
            // 为每个能力执行任务创建独立线程
            var thread = new Thread(task, "agent-capability-" + sequence.incrementAndGet());
            // 使用守护线程避免阻止应用退出
            thread.setDaemon(true);
            // 返回配置完成的执行线程
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
