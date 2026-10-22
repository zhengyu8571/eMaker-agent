package com.tianji.aigc.config;

import com.tianji.aigc.advisor.HybridKnowledgeAdvisor;
import com.tianji.aigc.runtime.AgentRuntime;
import com.tianji.aigc.debug.AiDebugTrace;
import io.micrometer.observation.ObservationRegistry;
import com.tianji.aigc.service.HybridRagService;
import com.tianji.aigc.support.retrieval.KnowledgeContextAssembler;
import com.tianji.aigc.support.retrieval.KnowledgeLibraryScopeResolver;
import com.tianji.aigc.memory.RedisChatMemoryRepository;
import com.tianji.common.constants.Constant;
import com.tianji.common.utils.WebUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class SpringAIConfig {

    @Value("${tj.ai.memory.max:100}")
    private Integer maxMessages;

    /**
     * 创建并配置自定义重试监听器Bean
     * <p>
     * 实现说明：
     * 1. 创建匿名RetryListener实现，在重试操作期间管理Web属性
     * 2. 将监听器注册到提供的RetryTemplate实例
     *
     * @param retryTemplate Spring Retry模板对象，用于注册重试监听器
     * @return RetryListener 已注册到模板的重试监听器实例，将由Spring容器管理
     */
    @Bean
    public RetryListener customizeRetryTemplate(RetryTemplate retryTemplate) {
        // 创建自定义重试监听器，实现以下核心功能：
        // - 重试开始时设置上下文标识
        // - 重试结束后清理上下文标识
        RetryListener retryListener = new RetryListener() {
            @Override
            public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
                WebUtils.setAttribute(Constant.SPRING_AI_ATTR, Constant.SPRING_AI_FLAG);
                return true;
            }

            @Override
            public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                WebUtils.removeAttribute(Constant.SPRING_AI_ATTR);
            }
        };

        // 将监听器注册到重试模板
        retryTemplate.registerListener(retryListener);
        return retryListener;
    }

    /**
     * 配置 ChatClient
     */
    @Bean
    public ChatClient chatClient(@Qualifier("dashscopeChatModel") ChatModel dashScopeChatModel,
                                 Advisor loggerAdvisor,  // 日志记录器
                                 ObservationRegistry observationRegistry, // 模型与客户端共享请求关联
                                 Advisor messageChatMemoryAdvisor // 对话记忆
    ) {
        return ChatClient.builder(dashScopeChatModel, observationRegistry, null)
                // 标签只用于内部记录，不发送给模型
                .defaultToolContext(java.util.Map.of(AiDebugTrace.MODEL_STAGE, "DIRECT"))
                .defaultAdvisors(loggerAdvisor, messageChatMemoryAdvisor) //添加 Advisor 功能增强
                .build();
    }

    /**
     * 路由专用客户端。路由属于内部控制流，不加载或写入正式会话记忆，
     * 也不挂载 RAG 与业务工具。
     */
    @Bean("routingChatClient")
    public ChatClient routingChatClient(@Qualifier("dashscopeChatModel") ChatModel dashScopeChatModel,
                                        ObservationRegistry observationRegistry,
                                        @Qualifier("loggerAdvisor") Advisor loggerAdvisor) {
        return ChatClient.builder(dashScopeChatModel, observationRegistry, null)
                // 标明这是路由模型，不与业务回答混淆
                .defaultToolContext(java.util.Map.of(AiDebugTrace.MODEL_STAGE, "ROUTING"))
                .defaultAdvisors(loggerAdvisor)
                .build();
    }

    /** 规划专用客户端只生成计划，不接入正式会话记忆和业务能力。 */
    @Bean("planningChatClient")
    public ChatClient planningChatClient(@Qualifier("dashscopeChatModel") ChatModel dashScopeChatModel,
                                         ObservationRegistry observationRegistry,
                                         @Qualifier("loggerAdvisor") Advisor loggerAdvisor) {
        // 保持规划调用与业务回答调用隔离
        return ChatClient.builder(dashScopeChatModel, observationRegistry, null).defaultToolContext(java.util.Map.of(AiDebugTrace.MODEL_STAGE, "PLANNING")).defaultAdvisors(loggerAdvisor).build();
    }

    /** 最终回答客户端不挂载记忆、RAG 和工具，避免中间结果写入正式会话。 */
    @Bean("finalAnswerChatClient")
    public ChatClient finalAnswerChatClient(@Qualifier("dashscopeChatModel") ChatModel dashScopeChatModel,
                                            ObservationRegistry observationRegistry,
                                            @Qualifier("loggerAdvisor") Advisor loggerAdvisor) {
        // 仅用模型整理已有的可信结果
        return ChatClient.builder(dashScopeChatModel, observationRegistry, null).defaultToolContext(java.util.Map.of(AiDebugTrace.MODEL_STAGE, "FINAL_ANSWER")).defaultAdvisors(loggerAdvisor).build();
    }

    /**
     * 问题改写专用客户端，不挂载记忆Advisor、RAG和业务工具。
     */
    @Bean("queryRewriteChatClient")
    public ChatClient queryRewriteChatClient(@Qualifier("dashscopeChatModel") ChatModel dashScopeChatModel,
                                             ObservationRegistry observationRegistry,
                                             @Qualifier("loggerAdvisor") Advisor loggerAdvisor) {
        return ChatClient.builder(dashScopeChatModel, observationRegistry, null)
                // 标明问题重写模型
                .defaultToolContext(java.util.Map.of(AiDebugTrace.MODEL_STAGE, "QUERY_REWRITE"))
                .defaultAdvisors(loggerAdvisor)
                .build();
    }

    @Bean("memoryChatClient")
    public ChatClient memoryChatClient(@Qualifier("dashscopeChatModel") ChatModel model, ObservationRegistry observationRegistry, @Qualifier("loggerAdvisor") Advisor loggerAdvisor) {
        // 记忆整理不挂正式记忆、RAG 或工具，避免递归调用
        return ChatClient.builder(model, observationRegistry, null).defaultToolContext(java.util.Map.of(AiDebugTrace.MODEL_STAGE, "MEMORY_UPDATE")).defaultAdvisors(loggerAdvisor).build();
    }

    @Bean
    public ChatClient openAiChatClient(@Qualifier("openAiChatModel") ChatModel openAiChatModel,
                                       ObservationRegistry observationRegistry,
                                       Advisor loggerAdvisor  // 日志记录器
    ) {
        return ChatClient.builder(openAiChatModel, observationRegistry, null)
                // 普通文本入口同样使用明确标签
                .defaultToolContext(java.util.Map.of(AiDebugTrace.MODEL_STAGE, "CHAT_TEXT"))
                .defaultAdvisors(loggerAdvisor)
                .build();
    }

    /**
     * 日志记录器
     */
    @Bean
    public Advisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    @Bean
    public RedisChatMemoryRepository redisChatMemoryRepository() {
        // 正式聊天记忆固定使用 Redis
        return new RedisChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        // 基于Redis实现，构造消息窗口记忆
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(this.maxMessages) // 最大消息数
                .build();
    }

    @Bean
    public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Bean
    public HybridKnowledgeAdvisor hybridKnowledgeAdvisor(
            HybridRagService hybridRagService,
            KnowledgeLibraryScopeResolver scopeResolver,
            KnowledgeContextAssembler contextAssembler,
            ChatMemory chatMemory,
            ObjectProvider<AgentRuntime> agentRuntimeProvider) {
        // 项目内部Advisor负责把混合检索结果接入官方ChatClient
        return new HybridKnowledgeAdvisor(
                hybridRagService, scopeResolver, contextAssembler, chatMemory, agentRuntimeProvider);
    }

}
