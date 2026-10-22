package com.tianji.aigc.capability;

import com.tianji.aigc.enums.AgentTypeEnum;

import java.util.Locale;

/**
 * 集中生成项目内置能力的稳定调用键。
 */
public final class CapabilityKeys {

    public static final String KNOWLEDGE_RAG = "rag.knowledge.retrieve";
    public static final String LOCAL_TOOL_PREFIX = "tool.local.";
    // MCP工具按服务ID隔离命名空间
    public static final String MCP_TOOL_PREFIX = "tool.mcp.";

    private CapabilityKeys() {
    }

    public static String agent(AgentTypeEnum agentType) {
        return "agent." + agentType.name().toLowerCase(Locale.ROOT);
    }

    public static String localTool(String toolName) {
        return LOCAL_TOOL_PREFIX + toolName;
    }

    // 生成包含来源服务的稳定能力键
    public static String mcpTool(String serverId, String toolName) {
        // 同名远端工具通过服务ID区分
        return MCP_TOOL_PREFIX + serverId + "." + toolName;
    }
}
