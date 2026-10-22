package com.tianji.aigc.runtime.plan;

/** 限定前端可见的计划进度类型。 */
public enum PlanProgressType {
    // 计划通过校验并准备执行
    PLAN_CREATED,
    // 单个步骤开始执行
    STEP_STARTED,
    // 单个步骤已经结束
    STEP_COMPLETED,
    // 正在评估任务目标
    EVALUATING,
    // 正在生成新一版计划
    REPLANNING
}
