package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 知识库查询链配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "tj.ai.knowledge.retrieval")
public class KnowledgeRetrievalProperties {

    // Query Rewrite相关配置
    private QueryRewrite queryRewrite = new QueryRewrite();

    // Redis向量召回相关配置
    private VectorRecall vectorRecall = new VectorRecall();

    // Elasticsearch BM25召回相关配置
    private Bm25Recall bm25Recall = new Bm25Recall();

    // 两路并行召回相关配置
    private ParallelRecall parallelRecall = new ParallelRecall();

    // RRF融合相关配置
    private Rrf rrf = new Rrf();

    // DashScope精排相关配置
    private Rerank rerank = new Rerank();

    // 最终模型上下文相关配置
    private Context context = new Context();

    // KnowledgeAgent默认检索范围相关配置
    private AgentScope agentScope = new AgentScope();

    @Data
    public static class QueryRewrite {

        // 是否开启问题改写
        private boolean enabled = true;

        // 最多读取的用户和助手消息数
        private int historyMessages = 6;

        // 历史对话允许使用的最大字符数
        private int maxHistoryChars = 4000;

        // 允许进入改写模型的问题最大字符数
        private int maxQuestionChars = 2000;
    }

    @Data
    public static class VectorRecall {

        // 向量召回的最大Chunk数量
        private int topK = 12;

        // 过滤低相关Chunk的相似度阈值
        private double similarityThreshold = 0.6d;
    }

    @Data
    public static class Bm25Recall {

        // BM25召回的最大Chunk数量
        private int topK = 12;
    }

    @Data
    public static class ParallelRecall {

        // 两路召回共用的最长等待时间
        private Duration timeout = Duration.ofSeconds(3);

        // 检索线程池常驻线程数
        private int corePoolSize = 4;

        // 检索线程池最大线程数
        private int maxPoolSize = 8;

        // 检索线程池等待队列容量
        private int queueCapacity = 100;
    }

    @Data
    public static class Rrf {

        // 平滑不同召回来源排名差异的常数
        private int rankConstant = 60;

        // 融合后保留的最大Chunk数量
        private int topK = 12;
    }

    @Data
    public static class Rerank {

        // 是否调用DashScope精排模型
        private boolean enabled = true;

        // 当前DashScope文本精排模型
        private String model = "gte-rerank-v2";

        // 精排后保留的最大Chunk数量
        private int topN = 8;

        // 等待精排模型响应的最长时间
        private Duration timeout = Duration.ofSeconds(5);
    }

    @Data
    public static class Context {

        // 允许交给回答模型的最大上下文Token数
        private int maxTokens = 4000;

        // Rerank成功时保留Chunk的最低相关度
        private double minRerankScore = 0.2d;
    }

    @Data
    public static class AgentScope {

        // 为空时由后端查询全部已启用知识库
        private Set<String> libraryKeys = Set.of();
    }
}
