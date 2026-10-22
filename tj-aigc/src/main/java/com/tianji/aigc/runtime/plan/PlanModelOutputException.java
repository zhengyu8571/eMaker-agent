package com.tianji.aigc.runtime.plan;

/** 表示模型输出无法解析为计划契约，可供 Planner 修复一次。 */
public class PlanModelOutputException extends IllegalArgumentException {

    public PlanModelOutputException(String message) {
        // 保存不包含原始模型输出的错误说明
        super(message);
    }

    public PlanModelOutputException(String message, Throwable cause) {
        // 保存解析失败原因供内部定位
        super(message, cause);
    }
}
