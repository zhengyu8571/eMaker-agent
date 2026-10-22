package com.tianji.aigc.runtime.model;

/**
 * 标识一次能力调用失败的稳定类型。
 */
public enum CapabilityFailureCode {

    // 当前Skill没有目标能力的执行权限
    ACCESS_DENIED,

    // 本次运行已经耗尽执行预算
    BUDGET_EXHAUSTED,

    // 本次运行已经被用户或系统取消
    CANCELLED,

    // 能力调用超过允许的执行时间
    TIMEOUT,

    // 能力调用输入不符合声明的约束
    INVALID_INPUT,

    // 能力内部执行失败
    EXECUTION_FAILED
}
