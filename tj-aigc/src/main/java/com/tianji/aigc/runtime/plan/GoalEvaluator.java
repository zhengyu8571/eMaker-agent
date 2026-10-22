package com.tianji.aigc.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 根据现有步骤结果和可信证据评估目标完成情况。 */
@Slf4j
@Component
public class GoalEvaluator {

    // 委托无业务能力的模型进行语义评估
    private final GoalEvaluationGateway gateway;
    // 将运行结果转换为结构化提示词
    private final ObjectMapper objectMapper;
    // 评估同样不能无限等待模型
    private final PlanModelCallExecutor modelCalls;

    public GoalEvaluator(GoalEvaluationGateway gateway, ObjectMapper objectMapper, PlanModelCallExecutor modelCalls) {
        // 保存评估模型入口
        this.gateway = gateway;
        // 保存 JSON 转换器
        this.objectMapper = objectMapper;
        // 与规划共用等待控制
        this.modelCalls = modelCalls;
    }

    public GoalEvaluation evaluate(String goal, AgentRunState state, AgentExecutionContext context) {
        // 已取消的运行不再评估或重规划
        context.cancellationToken().throwIfCancelled();
        // 只摘取步骤状态和失败原因，不传递内部对象
        var steps = new LinkedHashMap<String, Map<String, Object>>();
        // 按计划顺序整理运行结果
        for (var entry : state.stepResults().entrySet()) {
            // 保存当前步骤的状态与可公开失败原因
            steps.put(entry.getKey(), Map.of("status", entry.getValue().status().name(), "failureReason", entry.getValue().failureReason() == null ? "" : entry.getValue().failureReason()));
        }
        // 评估只读原目标、计划、结果和已脱敏证据
        var prompt = "用户原始目标：" + goal + "\n当前计划JSON：" + json(state.plan()) + "\n步骤状态JSON：" + json(steps) + "\n证据快照JSON：" + json(state.evidence());
        // 服务端记录一次模型评估调用
        context.budget().reserveModelCall();
        // 为本次评估预留估算 Token
        context.budget().reserveTokens(Math.max(1, prompt.length() / 4 + 256));
        // 模型只能判断完成、缺失、违规和是否需要重规划
        var result = this.modelCalls.call("GOAL_EVALUATE", context, () -> this.gateway.generate("你是证据充分性评估器，不是答案完成情况评估器。判断现有步骤与可信证据是否足以让后续最终回答器完成用户目标；此时最终答案尚未生成。制定学习计划、比较课程和把已知价格相加，都属于最终回答器的工作，绝不能仅因这些文本或计算尚未执行就列为 missingItems。若知识路径、所需课程大纲和价格已有可信证据，即使还没有写计划或加总，也应返回 completed=true、shouldReplan=false。只能根据输入判断，不调用工具、不假设不存在的结果；联调样例只能按样例数据说明，不能称为真实在售承诺。必要证据缺失、用户约束无法核实或步骤失败时才要求重规划；失败步骤不能算作完成。返回 GoalEvaluation JSON。", prompt));
        // 空评估不能作为继续执行的依据
        if (result == null) {
            // 明确报告无法判断目标
            throw new PlanModelOutputException("模型没有返回有效目标评估");
        }
        // 只记录评估状态与缺口数量，不写入模型原文
        log.info("PLAN 目标评估，completed={}，shouldReplan={}，missingCount={}，violationCount={}", result.completed(), result.shouldReplan(), result.missingItems().size(), result.violations().size());
        // 仅在显式开启调试日志时输出评估细节
        log.debug("PLAN 评估详情，missingItems={}，violations={}，feedback={}", result.missingItems(), result.violations(), result.feedback());
        // 任一步骤未成功时不能采信模型的完成声明
        var allSucceeded = state.stepResults().values().stream().allMatch(step -> step.status() == StepStatus.SUCCEEDED);
        // 计划步骤必须非空且全部成功才能完成
        if (allSucceeded && !state.stepResults().isEmpty()) {
            // 返回模型的语义评估结论
            return result;
        }
        // 模型误判完成时强制改为未完成并允许补救
        if (result.completed()) {
            // 保留模型缺失信息并追加失败事实
            return new GoalEvaluation(false, true, List.of("存在未成功的计划步骤"), result.violations(), "计划步骤未全部成功");
        }
        // 其他未完成结果仍由模型决定是否重规划
        return result;
    }

    private String json(Object value) {
        try {
            // 将已经脱敏的运行数据转成 JSON
            return this.objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // 无法序列化的状态不能用于评估
            throw new IllegalArgumentException("目标评估输入无法序列化", exception);
        }
    }
}
