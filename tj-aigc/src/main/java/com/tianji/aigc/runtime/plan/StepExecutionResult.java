package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.runtime.model.CapabilityExecutionResult;

/** 保存一个计划步骤的状态和能力执行结果。 */
public record StepExecutionResult(String stepId, StepStatus status, CapabilityExecutionResult capabilityResult, String failureReason) {
}
