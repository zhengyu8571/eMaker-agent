package com.tianji.aigc.capability.enums;

/**
 * 标识统一能力目录中的能力类型。
 */
public enum CapabilityKind {

    // 已有业务Agent能力
    AGENT,

    // 知识库检索能力
    RAG,

    // 本地Spring AI工具能力
    LOCAL_TOOL,

    // 后续阶段接入的MCP工具能力
    MCP_TOOL
}
