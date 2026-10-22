package com.tianji.aigc.runtime.plan;

import java.util.List;

/** 将确定性计划校验错误交给后续 Planner 修复流程。 */
public class PlanValidationException extends IllegalArgumentException {

    // 保存不含内部实现细节的校验错误
    private final List<String> errors;

    public PlanValidationException(List<String> errors) {
        // 使用首个错误生成简短异常消息
        super(errors == null || errors.isEmpty() ? "计划校验失败" : "计划校验失败：" + errors.get(0));
        // 固定本次校验发现的错误
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public List<String> errors() {
        // 返回可用于一次修复的错误列表
        return this.errors;
    }
}
