package com.tianji.aigc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建知识库并行召回使用的独立线程池。
 */
@Configuration
public class KnowledgeRetrievalExecutorConfig {

    @Bean(name = "knowledgeRetrievalExecutor", destroyMethod = "shutdown")
    public ExecutorService knowledgeRetrievalExecutor(
            KnowledgeRetrievalProperties retrievalProperties) {
        var properties = retrievalProperties.getParallelRecall();
        var sequence = new AtomicInteger();
        var corePoolSize = Math.max(1, properties.getCorePoolSize());
        var maxPoolSize = Math.max(corePoolSize, properties.getMaxPoolSize());

        // 使用有界队列，避免检索依赖变慢时任务无限堆积
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                task -> {
                    var thread = new Thread(
                            task,
                            "knowledge-retrieval-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
