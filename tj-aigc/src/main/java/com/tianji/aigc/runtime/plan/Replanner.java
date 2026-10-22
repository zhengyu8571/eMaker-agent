package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.agent.PlannerAgent;
import com.tianji.aigc.runtime.model.AgentExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 只在评估要求时触发一次受预算限制的重新规划。 */
@Component
public class Replanner {

    // 复用经过能力过滤和双次校验的规划器
    private final PlannerAgent planner;

    public Replanner(PlannerAgent planner) {
        // 保存统一规划入口
        this.planner = planner;
    }

    public ExecutionPlan replan(String goal, AgentRunState previous, GoalEvaluation evaluation, AgentExecutionContext context, Map<String, Object> userInput) {
        // 没有重规划结论时不得启动模型
        if (evaluation == null || !evaluation.shouldReplan() || evaluation.completed()) {
            // 防止普通完成路径误用重规划
            throw new IllegalArgumentException("当前评估不需要重规划");
        }
        // 由后端预算原子扣减一次重规划机会
        context.budget().reserveReplan();
        // 保持权限、已成功步骤和计划校验不变
        return this.planner.replan(goal, context, userInput, previous, evaluation);
    }
}
