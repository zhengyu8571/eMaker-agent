package com.tianji.aigc.runtime.plan;

/** 只判断目标达成情况，不调用业务能力。 */
public interface GoalEvaluationGateway {

    // 根据已执行结果返回结构化评估
    GoalEvaluation generate(String systemPrompt, String userPrompt);
}
