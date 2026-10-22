package com.tianji.aigc.runtime.plan;

/** 标记计划步骤的执行状态。 */
public enum StepStatus {
    // 等待依赖完成
    PENDING,
    // 正在执行能力
    RUNNING,
    // 能力执行成功
    SUCCEEDED,
    // 能力执行失败
    FAILED,
    // 因依赖或策略跳过
    SKIPPED,
    // 运行取消后终止
    CANCELLED
}
