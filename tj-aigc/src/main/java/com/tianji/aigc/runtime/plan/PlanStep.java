package com.tianji.aigc.runtime.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 保存单个计划步骤的能力、依赖和输入来源。 */
public record PlanStep(String id, String capabilityKey, List<String> dependsOn, List<InputBinding> inputBindings, String expectedOutput) {

    public PlanStep {
        // 固定依赖列表，避免校验后被调用方修改
        dependsOn = dependsOn == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(dependsOn));
        // 固定输入绑定，避免校验后被调用方修改
        inputBindings = inputBindings == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(inputBindings));
    }
}
