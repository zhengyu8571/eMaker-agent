package com.tianji.aigc.service.impl;

import com.tianji.aigc.service.EnhancedMemoryService;

import com.tianji.aigc.agent.AgentRegistry;
import com.tianji.aigc.agent.RouteAgent;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianji.aigc.config.AgentRuntimeProperties;
import com.tianji.aigc.config.RoutingProperties;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.runtime.AgentRunRegistry;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.service.GenerationStatusService;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 基于结构化路由工作流的聊天服务。
 */
@Slf4j
@Service
public class AgentServiceImpl implements ChatService {

    private static final String CLARIFICATION_MESSAGE =
            "我还不能确定你的意图。你是想了解技术知识、咨询某门课程、获得课程推荐，还是购买课程？";
    private static final ChatEventVO STOP_EVENT = ChatEventVO.builder()
            .eventType(ChatEventTypeEnum.STOP.getValue())
            .build();

    private final RouteAgent routeAgent;
    private final AgentRegistry agentRegistry;
    private final RoutingProperties routingProperties;
    private final GenerationStatusService generationStatusService;
    private final ChatSessionService chatSessionService;
    private final ChatMemory chatMemory;
    private final ChatClient openAiChatClient;
    private final SystemPromptConfig systemPromptConfig;
    // 统一分发 DIRECT 与 PLAN 请求
    private final RequestDispatcher requestDispatcher;
    // 控制统一Agent运行时是否生效
    private final AgentRuntimeProperties agentRuntimeProperties;
    // 保存当前进程内仍在执行的运行
    private final AgentRunRegistry agentRunRegistry;
    // 路由解析失败返回澄清前保留原因
    @Autowired(required = false)
    private AiDebugTrace debugTrace;
    // 路由只显式读取参考，不挂正式记忆 Advisor
    @Autowired(required = false)
    private EnhancedMemoryService enhancedMemory;

    public AgentServiceImpl(RouteAgent routeAgent,
                            AgentRegistry agentRegistry,
                            RoutingProperties routingProperties,
                            GenerationStatusService generationStatusService,
                            ChatSessionService chatSessionService,
                            ChatMemory chatMemory,
                            @Qualifier("openAiChatClient") ChatClient openAiChatClient,
                            SystemPromptConfig systemPromptConfig,
                            RequestDispatcher requestDispatcher,
                            AgentRuntimeProperties agentRuntimeProperties,
                            AgentRunRegistry agentRunRegistry) {
        // 保存结构化路由Agent
        this.routeAgent = routeAgent;
        // 保存业务Agent注册表
        this.agentRegistry = agentRegistry;
        // 保存路由置信度配置
        this.routingProperties = routingProperties;
        // 保存生成状态组件
        this.generationStatusService = generationStatusService;
        // 保存聊天会话组件
        this.chatSessionService = chatSessionService;
        // 保存官方聊天记忆组件
        this.chatMemory = chatMemory;
        // 保存普通文本问答客户端
        this.openAiChatClient = openAiChatClient;
        // 保存系统提示词配置
        this.systemPromptConfig = systemPromptConfig;
        // 保存统一请求分发器
        this.requestDispatcher = requestDispatcher;
        // 保存统一运行时配置
        this.agentRuntimeProperties = agentRuntimeProperties;
        // 保存活动运行注册表
        this.agentRunRegistry = agentRunRegistry;
    }

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        try {
            // 追问先补只读上下文，分发仍使用原始问题
            var routeResult = this.routeAgent.route(routingQuestion(question, sessionId));
            log.info("路由完成，sessionId={}，executionMode={}，agentType={}，skillId={}，confidence={}，needClarification={}",
                    sessionId, routeResult.executionMode(), routeResult.agentType(),
                    routeResult.skillId(), routeResult.confidence(), routeResult.needClarification());

            if (routeResult.needClarification()
                    || routeResult.confidence() < this.routingProperties.getMinConfidence()) {
                return this.clarify(question, sessionId);
            }

            return this.agentRegistry.find(routeResult.agentType())
                    .map(agent -> this.requestDispatcher.dispatch(agent, routeResult, question, sessionId))
                    .orElseGet(() -> {
                        log.warn("路由目标没有注册，sessionId={}，agentType={}", sessionId, routeResult.agentType());
                        return this.clarify(question, sessionId);
                    });
        } catch (Exception e) {
            // 与已保存的路由模型原文对应起来
            if (this.debugTrace != null) {
                // 不改变原固定澄清处理
                this.debugTrace.failure(this.debugTrace.currentId(), "ROUTING", e);
            }
            log.warn("路由失败，返回固定澄清提示，sessionId={}", sessionId, e);
            return this.clarify(question, sessionId);
        }
    }

    private String routingQuestion(String question, String sessionId) {
        // 关闭增强或匿名身份时保留原路由输入
        if (this.enhancedMemory == null || !this.enhancedMemory.enabled() || UserContext.getUser() == null) {
            // 不引入额外历史查询
            return question;
        }
        try {
            // 只读当前用户自己的正式历史
            var history = this.chatMemory.get(ChatService.getConversationId(sessionId));
            // 防止“继续上次那个”在业务 Agent 前就丢失主题
            return question + this.enhancedMemory.planningContext(UserContext.getUser(), sessionId, history);
        } catch (RuntimeException exception) {
            // 可选路由上下文故障不影响原分类
            log.warn("路由记忆读取失败，errorType={}", exception.getClass().getSimpleName());
            // 仍可只读取安全降级的摘要与偏好
            return question + this.enhancedMemory.context(UserContext.getUser(), sessionId);
        }
    }

    private Flux<ChatEventVO> clarify(String question, String sessionId) {
        var userId = UserContext.getUser();
        this.chatSessionService.update(sessionId, question, userId);

        var conversationId = ChatService.getConversationId(sessionId);
        this.chatMemory.add(conversationId, new UserMessage(question));
        this.chatMemory.add(conversationId, new AssistantMessage(CLARIFICATION_MESSAGE));

        var dataEvent = ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.DATA.getValue())
                .eventData(CLARIFICATION_MESSAGE)
                .build();
        return Flux.just(dataEvent, STOP_EVENT);
    }

    @Override
    public void stop(String sessionId) {
        // 先沿用生成状态停止机制
        this.generationStatusService.stop(sessionId);
        // 运行时关闭时无需传播取消令牌
        if (!this.agentRuntimeProperties.isEnabled()) {
            // 结束本次停止处理
            return;
        }
        // 读取当前用户避免误停其他用户会话
        var userId = UserContext.getUser();
        // 向当前活动运行传播停止信号
        this.agentRunRegistry.cancelBySession(userId, sessionId, "用户主动停止Agent运行");
    }

    @Override
    public String chatText(String question) {
        return this.openAiChatClient.prompt()
                .system(this.systemPromptConfig.getTextSystemMessage().get())
                .user(question)
                .call()
                .content();
    }
}
