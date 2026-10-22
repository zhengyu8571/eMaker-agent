package com.tianji.aigc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 更新任务与阻塞模型调用分池，避免后台线程互相等待。 */
@Configuration
public class MemoryExecutorConfig {
    @Bean(name = "memoryUpdateExecutor", destroyMethod = "shutdownNow")
    public ExecutorService memoryUpdateExecutor() {
        // 两个工作线程，最多等待一百个会话任务
        return executor("memory-update-", 100);
    }

    @Bean(name = "memoryModelExecutor", destroyMethod = "shutdownNow")
    public ExecutorService memoryModelExecutor() {
        // 同步模型调用另用两个线程与十个等待位
        return executor("memory-model-", 10);
    }

    private ExecutorService executor(String prefix, int capacity) {
        // 生成便于排查的线程序号
        var sequence = new AtomicInteger();
        // 使用有界队列，拒绝时不回到聊天线程执行
        return new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            // 为后台任务分配独立线程
            var thread = new Thread(task, prefix + sequence.incrementAndGet());
            // 后台记忆不阻止应用退出
            thread.setDaemon(true);
            // 返回配置好的线程
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
