package com.tianji.aigc.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.capability.enums.SideEffectLevel;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import com.tianji.aigc.runtime.plan.ExecutionPlan;
import com.tianji.aigc.runtime.plan.AgentRunState;
import com.tianji.aigc.runtime.plan.GoalEvaluation;
import com.tianji.aigc.runtime.plan.PlanCreationException;
import com.tianji.aigc.runtime.plan.PlanModelGateway;
import com.tianji.aigc.runtime.plan.PlanModelCallExecutor;
import com.tianji.aigc.runtime.plan.PlanModelOutputException;
import com.tianji.aigc.runtime.plan.PlanValidationException;
import com.tianji.aigc.runtime.plan.PlanValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按当前 Skill 的只读能力目录生成并校验结构化执行计划。 */
@Slf4j
@Component
public class PlannerAgent {

    // 调用不带业务增强的规划模型
    private final PlanModelGateway modelGateway;
    // 对模型计划做确定性校验
    private final PlanValidator validator;
    // 序列化用户输入和能力目录
    private final ObjectMapper objectMapper;
    // 约束规划和重规划的实际模型等待
    private final PlanModelCallExecutor modelCalls;

    public PlannerAgent(PlanModelGateway modelGateway, PlanValidator validator, ObjectMapper objectMapper, PlanModelCallExecutor modelCalls) {
        // 保存规划模型入口
        this.modelGateway = modelGateway;
        // 保存计划校验器
        this.validator = validator;
        // 保存 JSON 转换器
        this.objectMapper = objectMapper;
        // 所有计划模型调用使用相同等待边界
        this.modelCalls = modelCalls;
    }

    public ExecutionPlan plan(String goal, AgentExecutionContext context, Map<String, Object> userInput) {
        // 首次规划没有历史状态
        return generate(goal, context, userInput, null, null);
    }

    public ExecutionPlan replan(String goal, AgentExecutionContext context, Map<String, Object> userInput, AgentRunState previous, GoalEvaluation evaluation) {
        // 重规划必须基于上轮运行和明确评估
        if (previous == null || evaluation == null || !evaluation.shouldReplan()) {
            // 禁止无依据地重新调用规划模型
            throw new IllegalArgumentException("重规划缺少上轮状态或评估依据");
        }
        // 使用同一安全能力目录重新生成计划
        return generate(goal, context, userInput, previous, evaluation);
    }

    private ExecutionPlan generate(String goal, AgentExecutionContext context, Map<String, Object> userInput, AgentRunState previous, GoalEvaluation evaluation) {
        // 拒绝空目标
        if (goal == null || goal.isBlank()) {
            // 没有目标就无法生成计划
            throw new IllegalArgumentException("规划目标不能为空");
        }
        // 拒绝缺少运行权限的请求
        if (context == null) {
            // 不能在无 Skill 和预算的情况下规划
            throw new IllegalArgumentException("规划缺少运行上下文");
        }
        // 只公开本次 Skill 授权的可规划只读能力
        var catalog = context.capabilities().descriptors().stream()
                .filter(descriptor -> context.skill().allowedCapabilityKeys().contains(descriptor.key()))
                .filter(descriptor -> descriptor.plannerVisible() && descriptor.sideEffect() == SideEffectLevel.READ_ONLY)
                .sorted(Comparator.comparing(descriptor -> descriptor.key()))
                .map(descriptor -> new PlannerCapability(descriptor.key(), descriptor.description(), descriptor.inputSchema(), descriptor.outputSchema()))
                .toList();
        // 不允许向模型提供空目录后再猜能力
        if (catalog.isEmpty()) {
            // 明确终止不可规划的请求
            throw new PlanCreationException(List.of("当前 Skill 没有可规划的只读能力"));
        }
        // 将不可信用户输入固定为 JSON 数据
        var input = userInput == null ? Map.<String, Object>of() : new LinkedHashMap<>(userInput);
        // 规划约束由服务端提供而非模型决定
        var systemPrompt = "你是执行计划生成器。只生成 ExecutionPlan JSON，不执行能力。只能使用给定目录中的 capabilityKey；每步必须显式声明 dependsOn 和 inputBindings；绑定必须符合输入输出 Schema；不得加入写操作、运行预算或未授权能力。RAG 的 libraryKeys 只能绑定 USER_INPUT.libraryKeys，不能绑定 conversationId；RAG query 只写简短技术主题词，例如“Java泛型”，不要混入学习计划、时长、预算或课程要求。学习安排由后续最终回答器根据证据整理，不要增加用于“生成计划”的 RAG 步骤。课程候选的详情步骤只能绑定上游标量 courseId，sourcePath 写 courseId，不得写 courseIds.0 等数组下标。计划必须是无环依赖图。";
        // 将目标、Skill 说明、能力目录和剩余步骤上限交给模型
        var userPrompt = "目标：" + goal.strip() + "\nSkill说明：" + context.skill().instructions() + "\n用户输入JSON：" + json(input) + "\n可用能力目录JSON：" + json(catalog) + "\n最多待执行步骤数：" + context.budget().remainingSteps();
        // 重规划只能基于旧计划、运行状态和评估反馈
        if (previous != null) {
            // 按执行顺序提取步骤状态
            var statuses = new LinkedHashMap<String, String>();
            // 不把原始能力结果直接送回规划模型
            previous.stepResults().forEach((id, result) -> statuses.put(id, result.status().name()));
            // 整份新计划必须保留原样的已成功步骤
            userPrompt += "\n旧计划JSON：" + json(previous.plan()) + "\n旧步骤状态JSON：" + json(statuses) + "\n已脱敏证据JSON：" + json(previous.evidence()) + "\n目标评估JSON：" + json(evaluation) + "\n已成功步骤必须原样保留；失败或未执行步骤可以修改或删除，替代步骤加入后须移除已废弃的失败节点，并修正下游依赖及绑定；不要保留已确定无法执行的旧节点。新增及重跑步骤不得超过剩余预算。";
        }
        // 保存首次计划失败的可修复错误
        var errors = List.<String>of();
        // 最多允许首次规划和一次修复
        for (var attempt = 0; attempt < 2; attempt++) {
            // 中途取消时不再调用模型
            context.cancellationToken().throwIfCancelled();
            // 每次规划都占用一次模型调用预算
            context.budget().reserveModelCall();
            // 按提示词长度预留 Token 估算额度
            context.budget().reserveTokens(Math.max(1, (systemPrompt.length() + userPrompt.length()) / 4 + 256));
            try {
                // 请求结构化执行计划
                // 固定本轮提示，避免格式修复改变异步输入
                var currentPrompt = userPrompt;
                // 模型等待同时受取消和剩余总时间约束
                var plan = this.modelCalls.call(previous == null ? "PLANNING" : "REPLANNING", context, () -> this.modelGateway.generate(systemPrompt, currentPrompt));
                // 在执行前进行确定性校验
                if (previous == null) {
                    // 首次计划按完整步骤预算校验
                    this.validator.validate(plan, context, input);
                } else {
                    // 重规划验证剩余预算并保护已成功步骤
                    this.validator.validateReplan(plan, context, input, previous);
                }
                // 仅记录通过校验的步骤标识和能力键
                log.info("PLAN 校验通过，steps={}", plan.steps().stream().map(step -> step.id() + ":" + step.capabilityKey()).toList());
                // 显式开启调试日志时才查看输入绑定结构
                log.debug("PLAN 结构化步骤={}", plan);
                // 只返回通过校验的计划
                return plan;
            } catch (PlanValidationException exception) {
                // 保留校验器给出的具体问题
                errors = exception.errors();
            } catch (PlanModelOutputException exception) {
                // 将模型格式错误转为有限的修复反馈
                errors = List.of(exception.getMessage());
            }
            // 只记录安全校验错误，不记录模型原文和用户私有数据
            log.warn("PLAN 第{}次生成未通过校验，errors={}", attempt + 1, errors);
            // 仅首次失败后构造一次修复提示
            if (attempt == 0) {
                // 把错误作为数据反馈，不放宽原始能力权限
                userPrompt = userPrompt + "\n上次计划未通过校验，请仅修复这些错误：" + json(errors);
            }
        }
        // 第二次失败后明确终止规划
        throw new PlanCreationException(errors);
    }

    private String json(Object value) {
        try {
            // 把结构化数据转为模型可读的 JSON
            return this.objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // 不使用无法安全序列化的输入生成计划
            throw new IllegalArgumentException("规划输入无法序列化", exception);
        }
    }

    /** 限定发送给模型的能力字段。 */
    private record PlannerCapability(String capabilityKey, String description, String inputSchema, String outputSchema) {
    }
}
