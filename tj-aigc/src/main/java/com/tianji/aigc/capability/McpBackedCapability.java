package com.tianji.aigc.capability;

/** 保留MCP来源，供Skill和调用器验证服务白名单。 */
public interface McpBackedCapability extends ToolBackedCapability {
    // 返回配置中的稳定服务ID
    String serverId();
}
