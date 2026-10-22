package com.tianji.aigc.runtime.plan;

import com.tianji.aigc.runtime.AgentBudget;
import com.tianji.aigc.runtime.model.EvidenceSnapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 保存一次 PLAN 运行的当前步骤结果和证据快照。 */
public record AgentRunState(String runId, ExecutionPlan plan, Map<String, StepExecutionResult> stepResults, EvidenceSnapshot evidence, AgentBudget budget, int replanCount) {

    public AgentRunState {
        // 运行标识必须有效
        if (runId == null || runId.isBlank()) {
            // 拒绝无法定位的运行
            throw new IllegalArgumentException("Agent runId 不能为空");
        }
        // 计划和预算必须存在
        if (plan == null || budget == null) {
            // 拒绝缺少执行边界的状态
            throw new IllegalArgumentException("Agent 计划和预算不能为空");
        }
        // 固定步骤结果，避免外部修改状态快照
        stepResults = Collections.unmodifiableMap(new LinkedHashMap<>(stepResults == null ? Map.of() : stepResults));
        // 没有证据时使用空快照
        evidence = evidence == null ? EvidenceSnapshot.EMPTY : evidence;
        // 重规划次数不能为负数
        if (replanCount < 0) {
            // 拒绝无效计数
            throw new IllegalArgumentException("重规划次数不能为负数");
        }
    }

    public AgentRunState withStepResult(StepExecutionResult result, EvidenceSnapshot currentEvidence) {
        // 复制现有步骤结果
        var updated = new LinkedHashMap<>(this.stepResults);
        // 保存本次步骤状态
        updated.put(result.stepId(), result);
        // 返回新的只读状态快照
        return new AgentRunState(this.runId, this.plan, updated, currentEvidence, this.budget, this.replanCount);
    }

    public AgentRunState withEvidence(EvidenceSnapshot currentEvidence) {
        // 仅更新当前证据快照
        return new AgentRunState(this.runId, this.plan, this.stepResults, currentEvidence, this.budget, this.replanCount);
    }

    public AgentRunState withPlan(ExecutionPlan currentPlan, Map<String, StepExecutionResult> currentResults, EvidenceSnapshot currentEvidence, int currentReplanCount) {
        // 保存重规划后的计划、结果和重规划次数
        return new AgentRunState(this.runId, currentPlan, currentResults, currentEvidence, this.budget, currentReplanCount);
    }
}
