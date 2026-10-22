package com.tianji.aigc.skill.model;

/**
 * 记录一份被禁用Skill的来源和原因。
 */
public record SkillDiagnostic(
        String skillId,
        String resource,
        String reason
) {
}
