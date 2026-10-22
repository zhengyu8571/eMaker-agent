package com.tianji.aigc.runtime.plan;

/** 将结构化计划生成交给独立的大模型客户端。 */
public interface PlanModelGateway {

    // 只返回 ExecutionPlan，不执行任何能力
    ExecutionPlan generate(String systemPrompt, String userPrompt);
}
