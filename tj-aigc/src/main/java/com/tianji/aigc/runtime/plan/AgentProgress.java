package com.tianji.aigc.runtime.plan;

/** 对外只公开进度类型、步骤 ID 和执行状态。 */
public record AgentProgress(PlanProgressType type, String stepId, StepStatus status) {
}
