package com.tianji.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 保持MCP握手、SSE和POST路径一致的专用路由。 */
@Configuration
@ConditionalOnProperty(prefix = "tj.ai.mcp.gateway", name = "enabled", havingValue = "true")
public class McpGatewayRouteConfig {
    // 显式Java路由不继承配置路由的StripPrefix默认过滤器
    @Bean
    public RouteLocator courseMcpRoutes(RouteLocatorBuilder builder) {
        // 优先转发MCP原始路径，只对该路由关闭响应等待超时
        return builder.routes().route("ais-mcp", route -> route.order(-100).path("/ais/mcp/sse", "/ais/mcp/message").metadata("response-timeout", -1).uri("lb://aigc-service")).build();
    }
}
