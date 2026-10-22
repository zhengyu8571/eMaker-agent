package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.agent.PlannerAgent;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 串联规划、执行、评估和有界重规划。 */
@Component
public class AgentLoop {

    // 首次规划只生成经过校验的计划
    private final PlannerAgent planner;
    // 计划执行统一经过 AgentRuntime
    private final AgentExecutor executor;
    // 评估现有结果是否完成目标
    private final GoalEvaluator evaluator;
    // 仅在需要时执行受预算限制的重规划
    private final Replanner replanner;

    public AgentLoop(PlannerAgent planner, AgentExecutor executor, GoalEvaluator evaluator, Replanner replanner) {
        // 保存首次规划器
        this.planner = planner;
        // 保存计划执行器
        this.executor = executor;
        // 保存目标评估器
        this.evaluator = evaluator;
        // 保存重规划器
        this.replanner = replanner;
    }

    public AgentLoopResult run(String goal, AgentExecutionContext context, Map<String, Object> userInput) {
        // 未订阅进度时使用无操作监听器
        return run(goal, context, userInput, AgentProgressListener.NOOP);
    }

    public AgentLoopResult run(String goal, AgentExecutionContext context, Map<String, Object> userInput, AgentProgressListener listener) {
        // 允许调用方省略进度监听器
        var progress = listener == null ? AgentProgressListener.NOOP : listener;
        // 首次规划并确定性校验计划
        var plan = this.planner.plan(goal, context, userInput);
        // 只通知计划已创建，不发送计划 JSON
        notifyProgress(progress, PlanProgressType.PLAN_CREATED);
        // 执行首版计划
        var state = this.executor.execute(plan, context, userInput, progress);
        // 每轮执行后评估已有结果
        while (true) {
            // 通知前端正在评估目标
            notifyProgress(progress, PlanProgressType.EVALUATING);
            // 在固定运行上下文内判断目标达成情况
            var evaluation = this.evaluator.evaluate(goal, state, context);
            // 达标或评估不要求重规划时结束循环
            if (evaluation.completed() || !evaluation.shouldReplan()) {
                // 返回当前最终状态供后续答案层处理
                return new AgentLoopResult(state, evaluation);
            }
            // 通知前端正在重规划而不泄露模型原文
            notifyProgress(progress, PlanProgressType.REPLANNING);
            // 服务端扣减重规划预算并生成新计划
            plan = this.replanner.replan(goal, state, evaluation, context, userInput);
            // 新版计划通过校验后再发送安全进度
            notifyProgress(progress, PlanProgressType.PLAN_CREATED);
            // 复用成功步骤，只执行失败或新增步骤
            state = this.executor.resume(plan, state, context, userInput, progress);
        }
    }

    private void notifyProgress(AgentProgressListener listener, PlanProgressType type) {
        try {
            // 只发送固定的安全进度类型
            listener.onProgress(new AgentProgress(type, null, null));
        } catch (RuntimeException ignored) {
            // 前端进度消费失败不改变计划执行结果
        }
    }
}
