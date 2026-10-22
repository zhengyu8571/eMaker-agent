package com.tianji.aigc.service.impl;

import com.tianji.aigc.agent.Agent;
import com.tianji.aigc.agent.RoutingDecision;
import com.tianji.aigc.config.AgentRuntimeProperties;
import com.tianji.aigc.config.PlannerProperties;
import com.tianji.aigc.config.SkillFeatureProperties;
import com.tianji.aigc.enums.ExecutionMode;
import com.tianji.aigc.runtime.AgentExecutionContextFactory;
import com.tianji.aigc.runtime.AgentRunRegistry;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import com.tianji.aigc.runtime.plan.PlanChatService;
import com.tianji.aigc.skill.SkillRegistry;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 根据路由结果分发既有 DIRECT 或新增 PLAN 主链。 */
@Component
public class RequestDispatcher {

    // 解析本次请求激活的 Skill
    private final SkillRegistry skillRegistry;
    // 关闭后恢复阶段三调用链
    private final SkillFeatureProperties skillProperties;
    // 关闭后恢复阶段四 Skill 调用链
    private final AgentRuntimeProperties runtimeProperties;
    // 控制 PLAN 是否真正执行
    private final PlannerProperties plannerProperties;
    // 创建权限、预算、取消和证据上下文
    private final AgentExecutionContextFactory contextFactory;
    // 将运行暴露给停止接口
    private final AgentRunRegistry runRegistry;
    // 执行多步规划聊天
    private final PlanChatService planChatService;

    public RequestDispatcher(SkillRegistry skillRegistry, SkillFeatureProperties skillProperties, AgentRuntimeProperties runtimeProperties, PlannerProperties plannerProperties, AgentExecutionContextFactory contextFactory, AgentRunRegistry runRegistry, PlanChatService planChatService) {
        // 保存 Skill 注册表
        this.skillRegistry = skillRegistry;
        // 保存 Skill 开关
        this.skillProperties = skillProperties;
        // 保存统一运行时开关
        this.runtimeProperties = runtimeProperties;
        // 保存 PLAN 开关
        this.plannerProperties = plannerProperties;
        // 保存上下文工厂
        this.contextFactory = contextFactory;
        // 保存活动运行注册表
        this.runRegistry = runRegistry;
        // 保存 PLAN 聊天服务
        this.planChatService = planChatService;
    }

    public Flux<ChatEventVO> dispatch(Agent agent, RoutingDecision decision, String question, String sessionId) {
        // Skill 总开关关闭时保持旧版业务 Agent 调用
        if (!this.skillProperties.isEnabled()) {
            // 阶段三 DIRECT 路径不创建运行上下文
            return agent.processStream(question, sessionId);
        }
        // 在当前业务 Agent 范围内解析 Skill
        var skill = this.skillRegistry.resolve(decision.skillId(), decision.agentType());
        // 运行时关闭时保持阶段四 Skill 调用链
        if (!this.runtimeProperties.isEnabled()) {
            // 不把 PLAN 注入未启用的运行时
            return agent.processStream(question, sessionId, skill);
        }
        // 使用显式用户身份创建本次运行上下文
        var context = this.contextFactory.create(sessionId, UserContext.getUser(), skill);
        // 注册本次活动运行供停止接口取消
        this.runRegistry.register(context);
        try {
            // 只在开关开启且路由选择 PLAN 时进入多步链
            if (decision.executionMode() == ExecutionMode.PLAN && this.plannerProperties.isEnabled()) {
                // 整次 PLAN 复用同一个上下文
                return this.planChatService.stream(question, context);
            }
            // 其余请求完整沿用阶段六 DIRECT 业务 Agent
            return agent.processStream(question, sessionId, skill, context);
        } catch (RuntimeException exception) {
            // 同步组装失败时冻结未完成证据
            if (!context.evidenceCollector().isFrozen()) {
                // 阻止失败运行继续接收能力结果
                context.evidenceCollector().freeze();
            }
            // 清理失败运行
            this.runRegistry.remove(context.runId());
            // 将错误交给聊天入口现有边界处理
            throw exception;
        }
    }
}
