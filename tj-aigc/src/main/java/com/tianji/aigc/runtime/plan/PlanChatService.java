package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.capability.CapabilityKeys;
import com.tianji.aigc.debug.AiDebugTrace;
import org.springframework.beans.factory.annotation.Autowired;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.runtime.AgentBudgetExceededException;
import com.tianji.aigc.runtime.AgentRunRegistry;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.service.EnhancedMemoryService;
import com.tianji.aigc.support.retrieval.KnowledgeLibraryScopeResolver;
import com.tianji.aigc.vo.ChatEventVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** 在独立线程执行 PLAN，并只保存用户问题与最终回答。 */
@Component
@Slf4j
public class PlanChatService {

    // 执行有界规划循环
    private final AgentLoop agentLoop;
    // 根据可信证据生成最终答案
    private final FinalAnswerComposer answerComposer;
    // 映射现有聊天协议事件
    private final PlanEventMapper eventMapper;
    // 只写入最终用户与助手消息
    private final ChatMemory chatMemory;
    // 更新会话最后交互时间
    private final ChatSessionService chatSessionService;
    // 清理活动运行并支持停止接口
    private final AgentRunRegistry runRegistry;
    // 按已启用知识库解析本次允许的 RAG 范围
    private final KnowledgeLibraryScopeResolver libraryScopeResolver;
    // 保存最终原文被校验拒绝时的直接原因
    @Autowired(required = false)
    private AiDebugTrace debugTrace;
    // PLAN 显式读取增强记忆，不挂正式记忆 Advisor
    @Autowired(required = false)
    private EnhancedMemoryService enhancedMemory;

    public PlanChatService(AgentLoop agentLoop, FinalAnswerComposer answerComposer, PlanEventMapper eventMapper, ChatMemory chatMemory, ChatSessionService chatSessionService, AgentRunRegistry runRegistry, KnowledgeLibraryScopeResolver libraryScopeResolver) {
        // 保存规划循环
        this.agentLoop = agentLoop;
        // 保存最终回答器
        this.answerComposer = answerComposer;
        // 保存事件映射器
        this.eventMapper = eventMapper;
        // 保存正式聊天记忆
        this.chatMemory = chatMemory;
        // 保存会话更新组件
        this.chatSessionService = chatSessionService;
        // 保存活动运行注册表
        this.runRegistry = runRegistry;
        // 保存服务端知识库范围解析器
        this.libraryScopeResolver = libraryScopeResolver;
    }

    public Flux<ChatEventVO> stream(String question, AgentExecutionContext context) {
        // 发言时间不使用较晚的任务完成时刻
        var occurredAt = System.currentTimeMillis();
        // 在异步任务创建前捕获所属请求
        var observation = this.debugTrace == null ? null : this.debugTrace.currentObservation();
        // 订阅后才在有界弹性线程执行阻塞模型调用
        return Flux.create(sink -> {
            // 用户取消流时先取消共享运行
            var task = Schedulers.boundedElastic().schedule(() -> {
                // PLAN模型调用与入口路由使用同一个文件
                try (var ignored = this.debugTrace == null ? null : this.debugTrace.scope(observation)) {
                    // 使用显式用户身份更新会话
                    this.chatSessionService.update(context.sessionId(), question, context.userId());
                    // 给规划器提供最小结构化用户输入
                    var input = userInput(question, context);
                    // 读取近期原文、摘要与明确偏好
                    var goal = memoryGoal(question, context);
                    // 发送不含内部推理的进度事件
                    var result = this.agentLoop.run(goal, context, input, progress -> sink.next(this.eventMapper.progress(progress)));
                    // 取消后不再生成最终答案
                    context.cancellationToken().throwIfCancelled();
                    // 冻结所有能力产生的可信证据
                    context.evidenceCollector().freeze();
                    // 只基于当前证据和执行结果整理答案
                    var answer = this.answerComposer.compose(goal, result, context);
                    // 断流或停止后不写入正式记忆
                    if (sink.isCancelled() || context.cancellationToken().isCancelled()) {
                        // 结束已取消的后台任务
                        return;
                    }
                    // 使用与 DIRECT 相同的用户加会话隔离键
                    var conversationId = context.userId() + "_" + context.sessionId();
                    // 一次写入原始问题和最终答案，避免分两次保存留下半轮对话
                    this.chatMemory.add(conversationId, java.util.List.of(new UserMessage(question), new AssistantMessage(answer.text())));
                    // 未完成提示不作为成功任务进入增强记忆
                    if (answer.completed() && this.enhancedMemory != null) {
                        try {
                            // 不保存增强提示词或中间执行结果
                            this.enhancedMemory.recordCompleted(context.userId(), context.sessionId(), context.requestId(), question, answer.text(), occurredAt);
                        } catch (RuntimeException exception) {
                            // 已成功的任务不因增强记忆故障改成失败
                            log.warn("PLAN 增强记忆更新失败，errorType={}", exception.getClass().getSimpleName());
                        }
                    }
                    // 保持 DATA、PARAM、STOP 的既有语义
                    this.eventMapper.finalEvents(answer).forEach(sink::next);
                    // 关闭正常完成的事件流
                    sink.complete();
                } catch (RuntimeException exception) {
                    // 引用校验或结构化解析失败也留下错误行
                    if (this.debugTrace != null) {
                        // 模型原文已由模型观察器提前保存
                        this.debugTrace.failure(context.requestId(), "PLAN", exception);
                    }
                    // 在服务端保留失败类型，前端仍只接收安全提示
                    log.warn("PLAN 请求失败，runId={}，errorType={}，message={}", context.runId(), exception.getClass().getSimpleName(), exception.getMessage(), exception);
                    // 停止接口取消但前端仍在订阅时发送正常结束事件
                    if (!sink.isCancelled() && context.cancellationToken().isCancelled()) {
                        // 取消运行只返回 STOP，不写正式记忆
                        sink.next(ChatEventVO.builder().eventType(ChatEventTypeEnum.STOP.getValue()).build());
                        // 结束仍存活的订阅
                        sink.complete();
                    }
                    // 用户主动停止时不补发错误回答
                    if (!sink.isCancelled() && !context.cancellationToken().isCancelled()) {
                        // 将内部错误映射为有限的用户提示
                        var message = safeFailure(exception);
                        // 失败时只发送 DATA 与 STOP，不保存内部中间状态
                        this.eventMapper.finalEvents(new PlanFinalAnswer(message, java.util.List.of(), false)).forEach(sink::next);
                        // 正常结束可见失败提示
                        sink.complete();
                    }
                } finally {
                    // 失败或取消后也冻结残留证据
                    if (!context.evidenceCollector().isFrozen()) {
                        // 防止迟到结果进入已结束运行
                        context.evidenceCollector().freeze();
                    }
                    // 从停止接口可见的活动运行中删除本次请求
                    this.runRegistry.remove(context.runId());
                    // 清理课程工具的请求级临时结果
                    ToolResultHolder.remove(context.requestId());
                }
            });
            // 客户端取消流时传播取消令牌并中断后台任务
            sink.onCancel(() -> {
                // 标记整次 PLAN 已取消
                context.cancellationToken().cancel("客户端已取消 PLAN 流式回答");
                // 中断当前后台执行线程
                task.dispose();
            });
        });
    }

    private Map<String, Object> userInput(String question, AgentExecutionContext context) {
        // 不包含 RAG 时只提供用户原始问题
        if (context.capabilities().find(CapabilityKeys.KNOWLEDGE_RAG).isEmpty()) {
            // 不额外查询知识库目录
            return Map.of("question", question);
        }
        // 通过服务端范围解析器校验当前 Skill 的知识库
        var libraryKeys = this.libraryScopeResolver.resolve(context.skill().knowledgeBaseKeys());
        // 没有可检索知识库就不能执行 RAG 计划
        if (libraryKeys.isEmpty()) {
            // 阻止无限定范围的检索
            throw new IllegalStateException("当前 Skill 没有可用知识库");
        }
        // 固定用户问题和可信知识库范围供绑定使用
        return Map.of("question", question, "libraryKeys", java.util.List.copyOf(libraryKeys));
    }

    private String memoryGoal(String question, AgentExecutionContext context) {
        // 关闭时不额外读取原历史
        if (this.enhancedMemory == null || !this.enhancedMemory.enabled()) {
            // 保持原始目标
            return question;
        }
        try {
            // 原 ChatMemory 仍保存近期正式原文
            var history = this.chatMemory.get(context.userId() + "_" + context.sessionId());
            // 规划、评估和终答复用同一份参考
            return question + this.enhancedMemory.planningContext(context.userId(), context.sessionId(), history);
        } catch (RuntimeException exception) {
            // 可选历史读取失败不阻断原 PLAN
            log.warn("PLAN 增强记忆读取失败，errorType={}", exception.getClass().getSimpleName());
            // 摘要与偏好读取也有独立失败回退
            return question + this.enhancedMemory.context(context.userId(), context.sessionId());
        }
    }

    private String safeFailure(RuntimeException exception) {
        // 计划两次校验失败时返回明确提示
        if (exception instanceof PlanCreationException) {
            // 不暴露内部计划 JSON
            return "无法生成安全的执行计划，请把目标或约束说得更具体。";
        }
        // 运行预算耗尽时建议缩小目标
        if (exception instanceof AgentBudgetExceededException) {
            // 不暴露内部预算数值
            return "本次任务达到执行预算，请缩小问题范围后重试。";
        }
        // 其他执行失败不向前端暴露堆栈
        return "本次任务未能完成，请稍后重试。";
    }
}
