package com.tianji.aigc.runtime.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 保存用户目标和待执行步骤。 */
public record ExecutionPlan(String goal, List<PlanStep> steps) {

    public ExecutionPlan {
        // 固定步骤列表，具体合法性由 PlanValidator 校验
        steps = steps == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(steps));
    }
}
