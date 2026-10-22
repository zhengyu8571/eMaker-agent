package com.tianji.aigc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.mcp.McpCourseTools;
import com.tianji.aigc.mcp.McpEndpoints;
import com.tianji.aigc.mcp.McpTokenFilter;
import com.tianji.aigc.service.CourseQueryService;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/** 显式发布两个课程工具，避免自动收集其他工具。 */
@Configuration
@ConditionalOnProperty(prefix = "tj.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpServerConfig {
    // 注册服务Token校验，先于MVC登录拦截器执行
    @Bean
    public FilterRegistrationBean<McpTokenFilter> courseMcpTokenFilter(@Value("${tj.ai.mcp.server.token:}") String token) {
        // 构建Token过滤器，空Token会拒绝启动
        var registration = new FilterRegistrationBean<>(new McpTokenFilter(token));
        // 同时保护SSE连接和JSON-RPC消息
        registration.addUrlPatterns(McpEndpoints.SSE, McpEndpoints.MESSAGE);
        // 提前完成服务身份校验
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        // 返回Servlet过滤器注册
        return registration;
    }

    // 注册官方MVC SSE传输
    @Bean
    public WebMvcSseServerTransportProvider courseMcpTransport(ObjectMapper objectMapper) {
        // 固定消息与SSE端点，避免网关前缀错位
        return new WebMvcSseServerTransportProvider(objectMapper, McpEndpoints.MESSAGE, McpEndpoints.SSE);
    }

    // 将SDK路由交给Spring MVC
    @Bean
    public RouterFunction<ServerResponse> courseMcpRouter(WebMvcSseServerTransportProvider courseMcpTransport) {
        // 使用SDK提供的SSE和JSON-RPC处理器
        return courseMcpTransport.getRouterFunction();
    }

    // 应用关闭时释放服务端会话
    @Bean(destroyMethod = "close")
    public McpSyncServer courseMcpServer(WebMvcSseServerTransportProvider courseMcpTransport, CourseQueryService courseQueryService) {
        // 只解析无状态课程工具的两个方法
        var callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new McpCourseTools(courseQueryService))
                .build().getToolCallbacks();
        // 使用官方适配器将工具转换为MCP规范
        var tools = McpToolUtils.toSyncToolSpecifications(callbacks);
        // 构建只提供工具调用的同步Server
        return McpServer.sync(courseMcpTransport).serverInfo("tianji-course", "1.0.0").capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).tools(tools).build();
    }
}
