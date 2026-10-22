package com.tianji.aigc.mcp;

/** 固定MCP外部路径，网关和握手地址保持一致。 */
public final class McpEndpoints {
    // 接收服务端SSE事件
    public static final String SSE = "/ais/mcp/sse";
    // 向服务端发送JSON-RPC消息
    public static final String MESSAGE = "/ais/mcp/message";

    // 工具类不允许实例化
    private McpEndpoints() {
    }
}
