package com.tianji.aigc.runtime.plan;

import java.util.List;

/** 保存目标评估结论，不承担最终答案生成。 */
public record GoalEvaluation(boolean completed, boolean shouldReplan, List<String> missingItems, List<String> violations, String feedback) {

    public GoalEvaluation {
        // 固定缺失项列表
        missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
        // 固定约束违反列表
        violations = violations == null ? List.of() : List.copyOf(violations);
        // 避免空反馈污染后续提示词
        feedback = feedback == null ? "" : feedback.strip();
        // 完成目标时不得继续要求重规划
        if (completed && shouldReplan) {
            // 拒绝自相矛盾的评估结果
            throw new IllegalArgumentException("目标已完成时不能继续重规划");
        }
    }
}
