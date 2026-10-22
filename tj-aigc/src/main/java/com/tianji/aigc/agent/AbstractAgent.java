package com.tianji.aigc.agent;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.capability.CapabilityResolver;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.runtime.AgentRunRegistry;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import com.tianji.aigc.runtime.tool.RuntimeToolCallbackFactory;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.service.GenerationStatusService;
import com.tianji.aigc.service.EnhancedMemoryService;
import com.tianji.aigc.debug.AiDebugTrace;
import com.tianji.aigc.skill.model.ActivatedSkill;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一实现业务Agent的模型调用、流式输出和运行时生命周期。
 */
@Slf4j
public abstract class AbstractAgent implements Agent {

    // 提供所有Agent共用的流式结束事件
    public static final ChatEventVO STOP_EVENT = ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build();

    // 执行Spring AI模型调用
    @Resource
    private ChatClient chatClient;

    // 保存当前会话是否仍在生成
    @Resource
    private GenerationStatusService generationStatusService;

    // 保存停止生成时的部分回答
    @Resource
    private ChatMemory chatMemory;

    // 内部能力不写入顶层对话的增强记忆
    @Autowired(required = false)
    private EnhancedMemoryService enhancedMemory;
    // 旧链关闭 Runtime 时也保留后台记忆的请求关联
    @Autowired(required = false)
    private AiDebugTrace debugTrace;

    // 更新当前聊天会话时间
    @Resource
    private ChatSessionService chatSessionService;

    // 为旧链路解析Skill允许的原始工具回调
    @Resource
    private CapabilityResolver capabilityResolver;

    // 为运行时链路创建受保护的工具回调
    @Resource
    private RuntimeToolCallbackFactory runtimeToolCallbackFactory;

    // 清理本次请求对应的活动运行
    @Resource
    private AgentRunRegistry agentRunRegistry;

    @Override
    public Flux<ChatEventVO> processStream(String question, String sessionId) {
        // 无Skill请求保持阶段三原始链路
        return processStream(question, sessionId, null, null);
    }

    @Override
    public Flux<ChatEventVO> processStream(String question, String sessionId, ActivatedSkill skill) {
        // 无运行上下文时保持阶段四Skill链路
        return processStream(question, sessionId, skill, null);
    }

    @Override
    public Flux<ChatEventVO> processStream(String question, String sessionId, ActivatedSkill skill, AgentExecutionContext executionContext) {
        // 校验Skill与当前业务Agent一致
        validateSkill(skill);
        // 校验运行上下文没有跨Agent或跨会话复用
        validateExecutionContext(sessionId, skill, executionContext);
        // 优先复用上下文中的请求标识
        var requestId = executionContext == null ? generateRequestId() : executionContext.requestId();
        // 优先使用显式用户身份
        var userId = executionContext == null ? UserContext.getUser() : executionContext.userId();
        // 使用显式用户和会话生成对话标识
        var conversationId = userId + "_" + sessionId;
        // 使用请求开始时刻比较偏好新旧与清除时刻
        var occurredAt = System.currentTimeMillis();
        // 调试文件标识与旧链内部工具请求标识可能不同
        var traceId = this.debugTrace == null ? null : this.debugTrace.currentId();
        // 优先使用文件关联标识记录本轮记忆
        var memoryTurnId = traceId == null ? requestId : traceId;
        // 缓存已经发送给前端的模型文本
        var outputBuilder = new StringBuilder();
        try {
            // 在模型开始前扣减步骤、调用次数和输入Token预算
            reserveModelBudget(question, executionContext);
            // 更新当前会话的最后交互信息
            this.chatSessionService.update(sessionId, question, userId);
            // 组装当前Agent的模型请求
            var responseFlux = getChatClientRequest(question, sessionId, requestId, skill, executionContext).stream().chatResponse();
            // 处理上游因停止或断开触发的取消
            var cancellableResponses = responseFlux.doOnCancel(() -> {
                // 向统一运行时传播客户端取消
                cancelRun(executionContext, "客户端已取消流式回答");
                // 将已生成部分保存到正式对话记忆
                saveStopHistoryRecord(conversationId, outputBuilder.toString());
            });
            // 生成并返回前端事件流
            return cancellableResponses
                    .doFirst(() -> this.generationStatusService.start(sessionId))
                    .takeWhile(response -> this.generationStatusService.isGenerating(sessionId))
                    .map(chatResponse -> {
                        // 读取本次模型增量文本
                        var text = chatResponse.getResult().getOutput().getText();
                        // 将输出Token估算计入总预算
                        reserveOutputTokens(text, executionContext);
                        // 保存已经输出的增量文本
                        outputBuilder.append(text);
                        // 读取模型返回的结束原因
                        var finishReason = chatResponse.getResult().getMetadata().getFinishReason();
                        // 判断模型是否已经正常结束
                        if (StrUtil.equals(finishReason, Constant.STOP)) {
                            // 读取模型消息标识
                            var messageId = chatResponse.getMetadata().getId();
                            // 建立消息标识到当前请求标识的关联
                            ToolResultHolder.put(messageId, Constant.REQUEST_ID, requestId);
                        }
                        // 将模型增量转换为前端数据事件
                        return ChatEventVO.builder().eventData(text).eventType(ChatEventTypeEnum.DATA.getValue()).build();
                    })
                    .concatWith(Flux.defer(() -> appendFinalEvents(requestId)))
                    // 完整结束后才更新增强记忆
                    .doOnComplete(() -> rememberCompleted(userId, sessionId, memoryTurnId, question, outputBuilder.toString(), occurredAt, executionContext))
                    .doFinally(signalType -> cleanupStream(sessionId, requestId, executionContext));
        } catch (RuntimeException exception) {
            // 请求组装失败时立即释放已经注册的运行
            cleanupStream(sessionId, requestId, executionContext);
            // 将原始异常交给上层路由边界处理
            throw exception;
        }
    }

    @Override
    public String process(String question, String sessionId) {
        // 无Skill请求保持原始同步链路
        return process(question, sessionId, null, null);
    }

    @Override
    public String process(String question, String sessionId, ActivatedSkill skill) {
        // 无运行上下文时保持阶段四同步链路
        return process(question, sessionId, skill, null);
    }

    @Override
    public String process(String question, String sessionId, ActivatedSkill skill, AgentExecutionContext executionContext) {
        // 校验Skill与当前业务Agent一致
        validateSkill(skill);
        // 校验运行上下文没有跨Agent或跨会话复用
        validateExecutionContext(sessionId, skill, executionContext);
        // 优先复用上下文中的请求标识
        var requestId = executionContext == null ? generateRequestId() : executionContext.requestId();
        // 优先使用显式用户身份
        var userId = executionContext == null ? UserContext.getUser() : executionContext.userId();
        try {
            // 在模型开始前扣减步骤、调用次数和输入Token预算
            reserveModelBudget(question, executionContext);
            // 更新当前会话的最后交互信息
            this.chatSessionService.update(sessionId, question, userId);
            // 同步执行当前Agent模型请求
            var answer = getChatClientRequest(question, sessionId, requestId, skill, executionContext).call().content();
            // 将完整回答的Token估算计入总预算
            reserveOutputTokens(answer, executionContext);
            // 返回模型生成的完整回答
            return answer;
        } finally {
            // 同步请求结束后冻结证据并清理活动运行
            cleanupRun(requestId, executionContext);
        }
    }

    private ChatClient.ChatClientRequestSpec getChatClientRequest(String question, String sessionId, String requestId, ActivatedSkill skill, AgentExecutionContext executionContext) {
        // 后台调用优先使用显式用户身份
        var userId = executionContext == null ? UserContext.getUser() : executionContext.userId();
        // 只追加低信任参考，不替代 Skill 与当前业务证据
        var memory = this.enhancedMemory == null ? "" : this.enhancedMemory.context(userId, sessionId);
        // 增强上下文也计入输入预算
        if (executionContext != null && !memory.isEmpty()) {
            // 不让摘要绕过 Token 上限
            executionContext.budget().reserveTokens(estimateTokens(memory));
        }
        // 创建当前Agent的基础模型请求
        var requestSpec = this.chatClient.prompt()
                .system(resolveSystemMessage(skill) + memory)
                .advisors(advisorSpec -> advisorSpec.advisors(this.advisors()).params(resolveAdvisorParams(sessionId, requestId, skill, executionContext)))
                .toolContext(resolveToolContext(sessionId, requestId, skill, executionContext))
                .user(question);
        // 无Skill调用保持阶段三原始工具注册方式
        if (skill == null) {
            // 返回注册Agent原始工具对象的请求
            return requestSpec.tools(this.tools());
        }
        // 有运行上下文时只注册受AgentRuntime保护的工具回调
        if (executionContext != null) {
            // 返回注册运行时工具回调的请求
            return requestSpec.toolCallbacks(this.runtimeToolCallbackFactory.create(executionContext));
        }
        // 运行时关闭时保持阶段四的Skill工具白名单
        return requestSpec.toolCallbacks(this.capabilityResolver.resolveToolCallbacks(skill));
    }

    private String resolveSystemMessage(ActivatedSkill skill) {
        // 先渲染原系统提示词
        var basePrompt = new PromptTemplate(this.systemMessage()).render(this.systemMessageParams());
        // 无Skill时直接使用原提示词
        if (skill == null) {
            // 返回原系统提示词
            return basePrompt;
        }
        // 追加当前Skill身份和行为说明
        var prompt = new StringBuilder(basePrompt).append("\n\n<active_skill id=\"").append(skill.id()).append("\">\n").append(skill.instructions());
        // 存在安全加载的引用内容时一并追加
        if (!skill.referenceContext().isBlank()) {
            // 使用明确标签隔离Skill引用内容
            prompt.append("\n\n<skill_references>\n").append(skill.referenceContext()).append("\n</skill_references>");
        }
        // 闭合Skill提示词标签并返回
        return prompt.append("\n</active_skill>").toString();
    }

    private Map<String, Object> resolveAdvisorParams(String sessionId, String requestId, ActivatedSkill skill, AgentExecutionContext executionContext) {
        // 复制业务Agent提供的原始Advisor参数
        var params = new LinkedHashMap<>(this.advisorParams(sessionId, requestId));
        // 有显式上下文时覆盖可能依赖ThreadLocal的身份参数
        if (executionContext != null) {
            // 写入稳定的对话标识
            params.put(ChatMemory.CONVERSATION_ID, executionContext.userId() + "_" + sessionId);
            // 供Advisor调用统一运行时
            params.put(Constant.AGENT_EXECUTION_CONTEXT, executionContext);
        }
        // 有Skill时追加Skill和知识库范围
        if (skill != null) {
            // 写入当前Skill标识
            params.put(Constant.ACTIVE_SKILL_ID, skill.id());
            // 写入当前Skill允许的知识库范围
            params.put(Constant.KNOWLEDGE_LIBRARY_KEYS, skill.knowledgeBaseKeys());
        }
        // 返回不可修改的Advisor参数
        return Map.copyOf(params);
    }

    private Map<String, Object> resolveToolContext(String sessionId, String requestId, ActivatedSkill skill, AgentExecutionContext executionContext) {
        // 复制业务Agent提供的原始工具上下文
        var context = new LinkedHashMap<>(this.toolContext(sessionId, requestId));
        // 始终写入当前请求标识
        context.put(Constant.REQUEST_ID, requestId);
        // 有显式上下文时覆盖可能依赖ThreadLocal的用户身份
        if (executionContext != null) {
            // 写入当前运行用户标识
            context.put(Constant.USER_ID, executionContext.userId());
        }
        // 有Skill时追加当前Skill标识
        if (skill != null) {
            // 写入当前Skill标识
            context.put(Constant.ACTIVE_SKILL_ID, skill.id());
        }
        // 返回不可修改的工具上下文
        return Map.copyOf(context);
    }

    private Flux<ChatEventVO> appendFinalEvents(String requestId) {
        // 读取工具和RAG保存的结构化参数
        var result = ToolResultHolder.get(requestId);
        // 有结构化参数时先返回参数事件
        if (ObjectUtil.isNotEmpty(result)) {
            // 在发送前删除进程内临时参数
            ToolResultHolder.remove(requestId);
            // 返回参数事件和结束事件
            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.PARAM.getValue()).eventData(result).build(), STOP_EVENT);
        }
        // 没有结构化参数时只返回结束事件
        return Flux.just(STOP_EVENT);
    }

    private void validateSkill(ActivatedSkill skill) {
        // 有Skill时必须与当前业务Agent一致
        if (skill != null && skill.agentType() != this.getAgentType()) {
            // 阻止错误Skill进入其他业务Agent
            throw new IllegalArgumentException("Skill与业务Agent不匹配：" + skill.id() + " -> " + this.getAgentType());
        }
    }

    private void validateExecutionContext(String sessionId, ActivatedSkill skill, AgentExecutionContext executionContext) {
        // 旧链路没有运行上下文时无需校验
        if (executionContext == null) {
            // 结束本次上下文校验
            return;
        }
        // 运行时链路必须同时提供Skill
        if (skill == null) {
            // 拒绝缺少权限范围的运行
            throw new IllegalArgumentException("Agent运行上下文必须绑定Skill");
        }
        // 运行上下文必须属于当前业务Agent
        if (executionContext.agentType() != this.getAgentType()) {
            // 阻止跨Agent复用上下文
            throw new IllegalArgumentException("Agent运行上下文与业务Agent不匹配");
        }
        // 运行上下文必须属于当前会话
        if (!executionContext.sessionId().equals(sessionId)) {
            // 阻止跨会话复用上下文
            throw new IllegalArgumentException("Agent运行上下文与会话不匹配");
        }
        // 运行上下文必须绑定同一个Skill
        if (!executionContext.skill().id().equals(skill.id())) {
            // 阻止跨Skill复用上下文
            throw new IllegalArgumentException("Agent运行上下文与Skill不匹配");
        }
    }

    private void reserveModelBudget(String question, AgentExecutionContext executionContext) {
        // 旧链路不启用统一预算
        if (executionContext == null) {
            // 结束本次预算扣减
            return;
        }
        // 阻止已经取消的运行开始模型调用
        executionContext.cancellationToken().throwIfCancelled();
        // 将顶层模型回答计为一个执行步骤
        executionContext.budget().reserveStep();
        // 扣减一次顶层模型调用预算
        executionContext.budget().reserveModelCall();
        // 扣减用户问题对应的输入Token估算
        executionContext.budget().reserveTokens(estimateTokens(question));
    }

    private void reserveOutputTokens(String text, AgentExecutionContext executionContext) {
        // 旧链路不启用统一Token预算
        if (executionContext == null) {
            // 结束本次输出预算扣减
            return;
        }
        // 阻止已经取消的运行继续发送模型结果
        executionContext.cancellationToken().throwIfCancelled();
        // 扣减本次模型输出的Token估算
        executionContext.budget().reserveTokens(estimateTokens(text));
    }

    private int estimateTokens(String text) {
        // 空文本仍按一个Token计入预算
        var length = text == null ? 0 : text.length();
        // 按约四个字符一个Token向上取整
        return Math.max(1, (length + 3) / 4);
    }

    private void cancelRun(AgentExecutionContext executionContext, String reason) {
        // 旧链路没有取消令牌
        if (executionContext == null) {
            // 结束本次取消传播
            return;
        }
        // 只保留第一次取消原因
        executionContext.cancellationToken().cancel(reason);
    }

    private void cleanupStream(String sessionId, String requestId, AgentExecutionContext executionContext) {
        // 清除当前会话的生成中标记
        this.generationStatusService.stop(sessionId);
        // 冻结证据并清理当前活动运行
        cleanupRun(requestId, executionContext);
    }

    private void rememberCompleted(Long userId, String sessionId, String requestId, String question, String answer, long occurredAt, AgentExecutionContext context) {
        // 停止、取消或未装配时不记录半轮回答
        if (this.enhancedMemory == null || !this.generationStatusService.isGenerating(sessionId) || (context != null && context.cancellationToken().isCancelled())) {
            // 原有部分回答保存逻辑保持不变
            return;
        }
        try {
            // 同步内部 Agent 能力不会经过此顶层完成钩子
            this.enhancedMemory.recordCompleted(userId, sessionId, requestId, question, answer, occurredAt);
        } catch (RuntimeException exception) {
            // 增强存储不能把成功 SSE 改成失败
            log.warn("DIRECT 增强记忆更新失败，errorType={}", exception.getClass().getSimpleName());
        }
    }

    private void cleanupRun(String requestId, AgentExecutionContext executionContext) {
        // 删除当前请求遗留的临时工具参数
        ToolResultHolder.remove(requestId);
        // 旧链路没有需要清理的统一运行
        if (executionContext == null) {
            // 结束本次运行清理
            return;
        }
        // 尚未冻结时固定最终证据快照
        if (!executionContext.evidenceCollector().isFrozen()) {
            // 禁止迟到能力结果继续写入证据
            executionContext.evidenceCollector().freeze();
        }
        // 从活动运行注册表删除当前运行
        this.agentRunRegistry.remove(executionContext.runId());
    }

    /**
     * 保存停止输出的部分回答。
     */
    private void saveStopHistoryRecord(String conversationId, String content) {
        // 将已经输出的文本保存为助手消息
        this.chatMemory.add(conversationId, new AssistantMessage(content));
    }

    private String generateRequestId() {
        // 生成旧链路使用的唯一请求标识
        return IdUtil.fastSimpleUUID();
    }

    @Override
    public void stop(String sessionId) {
        // 清除当前会话的生成中标记
        this.generationStatusService.stop(sessionId);
    }

    @Override
    public Map<String, Object> advisorParams(String sessionId, String requestId) {
        // 使用当前线程用户和会话生成旧链路对话标识
        var conversationId = ChatService.getConversationId(sessionId);
        // 返回记忆和请求关联参数
        return Map.of(ChatMemory.CONVERSATION_ID, conversationId, Constant.REQUEST_ID, requestId);
    }
}
