package com.tianji.aigc.skill.model;

import com.tianji.aigc.enums.AgentTypeEnum;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一次请求实际使用的Skill只读快照。
 * Skill解析和动态装配将在阶段四后续步骤实现。
 */
public record ActivatedSkill(

        // 跨环境稳定的Skill标识
        String id,

        // Skill绑定的业务Agent类型
        AgentTypeEnum agentType,

        // 追加到业务Agent系统提示词的角色与行为说明
        String instructions,

        // 经过安全加载和限长处理的短引用内容
        String referenceContext,

        // 本次RAG允许查询的知识库业务键
        Set<String> knowledgeBaseKeys,

        // 本次请求允许使用的统一能力键
        Set<String> allowedCapabilityKeys,

        // 后续MCP阶段允许接入的服务器标识
        Set<String> allowedMcpServers,

        // 后续Reflection阶段是否允许答案反思
        boolean reflectionEnabled
) {

    public ActivatedSkill {
        id = requireText(id, "Skill ID不能为空");
        if (agentType == null) {
            throw new IllegalArgumentException("Skill绑定的Agent类型不能为空");
        }
        instructions = requireText(instructions, "Skill说明不能为空");
        referenceContext = referenceContext == null ? "" : referenceContext.strip();
        knowledgeBaseKeys = normalizeSet(knowledgeBaseKeys);
        allowedCapabilityKeys = normalizeSet(allowedCapabilityKeys);
        allowedMcpServers = normalizeSet(allowedMcpServers);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.strip());
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}
