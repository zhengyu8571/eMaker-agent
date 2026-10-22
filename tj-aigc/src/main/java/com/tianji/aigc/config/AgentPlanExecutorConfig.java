package com.tianji.aigc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 为并行计划步骤提供独立的有界线程池。 */
@Configuration
public class AgentPlanExecutorConfig {

    @Bean(name = "agentPlanModelExecutor", destroyMethod = "shutdownNow")
    public ExecutorService agentPlanModelExecutor() {
        // 给模型调用分配独立线程名称
        var sequence = new AtomicInteger();
        // 使用两个线程和十六个等待位
        return new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            // 慢模型不占用DAG步骤线程
            var thread = new Thread(task, "agent-plan-model-" + sequence.incrementAndGet());
            // 不让辅助线程阻止退出
            thread.setDaemon(true);
            // 返回配置后的线程
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "agentPlanStepExecutor", destroyMethod = "shutdown")
    public ExecutorService agentPlanStepExecutor(AgentRuntimeProperties properties) {
        // 单次计划最多同时执行四个步骤
        var threads = Math.max(1, Math.min(4, properties.getCorePoolSize()));
        // 限制不同请求等待执行的步骤总数
        var queueCapacity = Math.max(1, Math.min(64, properties.getQueueCapacity()));
        // 给计划线程分配可辨识的名称
        var sequence = new AtomicInteger();
        // 使用独立线程池，避免占用公共 ForkJoinPool
        return new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity), task -> {
            // 创建当前计划步骤的线程
            var thread = new Thread(task, "agent-plan-step-" + sequence.incrementAndGet());
            // 不让辅助线程阻止服务关闭
            thread.setDaemon(true);
            // 返回已配置的线程
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
