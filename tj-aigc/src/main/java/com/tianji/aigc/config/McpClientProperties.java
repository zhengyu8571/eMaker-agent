package com.tianji.aigc.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** MCP连接配置不生成toString，避免打印服务Token。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tj.ai.mcp.client")
public class McpClientProperties {
    // 关闭时不注册任何MCP能力
    private boolean enabled;
    // 首次连接和握手的等待上限
    private Duration initializationTimeout = Duration.ofSeconds(3);
    // 课程详情含多个下游查询，预留冷启动等待
    private Duration requestTimeout = Duration.ofSeconds(15);
    // 失败后首次重连的冷却时间
    private Duration initialBackoff = Duration.ofSeconds(1);
    // 重连冷却时间的最大值
    private Duration maxBackoff = Duration.ofSeconds(30);
    // 可解析的工具响应字符上限
    private int maxResponseChars = 200_000;
    // 按稳定服务ID配置连接和工具授权
    private Map<String, Server> servers = new LinkedHashMap<>();

    // 单个服务的连接配置
    @Getter
    @Setter
    public static class Server {
        // 关闭后不进入能力目录
        private boolean enabled = true;
        // MCP服务的HTTP源地址
        private String url = "http://127.0.0.1:8094";
        // SDK握手使用的SSE路径
        private String sseEndpoint = "/ais/mcp/sse";
        // GET和POST均携带的服务Token
        private String token = "";
        // 只允许接入这两个已知只读课程工具
        private Set<String> allowedTools = Set.of("searchCourseCandidate", "queryCourseById");
    }
}
