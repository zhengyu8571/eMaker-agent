package com.tianji.gateway.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "tj.auth")
public class AuthProperties implements InitializingBean {

    private Set<String> excludePath;

    // 开启专用路由后由AIGC服务验证MCP Token
    @Value("${tj.ai.mcp.gateway.enabled:false}")
    private boolean mcpGatewayEnabled;

    @Override
    public void afterPropertiesSet() throws Exception {
        // 添加默认不拦截的路径
        excludePath.add("/error/**");
        excludePath.add("/jwks");
        excludePath.add("/accounts/login");
        excludePath.add("/accounts/admin/login");
        excludePath.add("/accounts/refresh");
        // 仅放行MCP实际使用的GET和POST端点
        if (this.mcpGatewayEnabled) {
            // SSE连接使用服务Token
            excludePath.add("GET:/ais/mcp/sse");
            // JSON-RPC消息使用同一服务Token
            excludePath.add("POST:/ais/mcp/message");
        }
    }
}
