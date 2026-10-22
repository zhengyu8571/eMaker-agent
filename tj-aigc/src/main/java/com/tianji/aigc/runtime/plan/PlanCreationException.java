package com.tianji.aigc.runtime.plan;

import java.util.List;

/** 表示两次计划生成都未通过确定性校验。 */
public class PlanCreationException extends IllegalStateException {

    // 保存最后一次可公开的校验错误
    private final List<String> errors;

    public PlanCreationException(List<String> errors) {
        // 向调用方说明计划无法安全执行
        super("无法生成合法的执行计划");
        // 固定最后一次错误列表
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public List<String> errors() {
        // 供上层返回明确失败原因
        return this.errors;
    }
}
