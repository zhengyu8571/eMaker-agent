package com.tianji.aigc.runtime.plan;

/** 保存 Plan—Execute—Evaluate 循环的终态。 */
public record AgentLoopResult(AgentRunState state, GoalEvaluation evaluation) {

    public AgentLoopResult {
        // 终态必须包含运行结果
        if (state == null || evaluation == null) {
            // 阻止后续答案层收到不完整状态
            throw new IllegalArgumentException("Agent循环缺少运行状态或目标评估");
        }
    }
}
