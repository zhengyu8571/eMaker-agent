package com.tianji.aigc.skill.model;

import com.tianji.aigc.enums.AgentTypeEnum;

import java.util.List;
import java.util.Set;

/**
 * 保存一份已经解析、尚未注册的Skill定义。
 */
public record SkillDefinition(

        // SKILL.md front matter中的稳定标识
        String id,

        // 负责执行该Skill的业务Agent
        AgentTypeEnum agentType,

        // 提供给路由模型的短说明
        String description,

        // 提供给页面或日志的名称
        String displayName,

        // SKILL.md正文中的角色和回答规则
        String instructions,

        // 安全加载并限长后的引用内容
        String referenceContext,

        // 允许检索的知识库稳定业务键
        Set<String> knowledgeBaseKeys,

        // Spring AI本地ToolCallback名称白名单
        Set<String> allowedTools,

        // 后续阶段允许使用的MCP服务器
        Set<String> allowedMcpServers,

        // 后续阶段是否启用答案反思
        boolean reflectionEnabled,

        // 是否作为绑定Agent的默认策略
        boolean defaultSkill,

        // 引用声明，保留给启动诊断使用
        List<SkillReference> references
) {

    public SkillDefinition {
        knowledgeBaseKeys = knowledgeBaseKeys == null ? Set.of() : Set.copyOf(knowledgeBaseKeys);
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        allowedMcpServers = allowedMcpServers == null ? Set.of() : Set.copyOf(allowedMcpServers);
        references = references == null ? List.of() : List.copyOf(references);
    }
}
