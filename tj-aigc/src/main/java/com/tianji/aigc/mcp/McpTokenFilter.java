package com.tianji.aigc.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 使用固定服务Token保护MCP的GET和POST端点。 */
public class McpTokenFilter extends OncePerRequestFilter {
    // 缓存Token摘要，避免日志或异常携带明文
    private final byte[] expectedDigest;

    // 开启服务端时必须提供Token
    public McpTokenFilter(String token) {
        // 空Token不能启动受保护端点
        if (token == null || token.isBlank()) {
            // 配置错误只给出变量名称
            throw new IllegalArgumentException("开启MCP Server必须配置TJ_MCP_SERVER_TOKEN");
        }
        // 对完整Authorization值计算固定长度摘要
        this.expectedDigest = digest("Bearer " + token);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 读取不含应用上下文前缀的请求路径
        var path = request.getServletPath();
        // 仅保护两个协议端点
        return !McpEndpoints.SSE.equals(path) && !McpEndpoints.MESSAGE.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        // 获取调用方携带的服务Token
        var authorization = request.getHeader("Authorization");
        // 常量时间比较固定长度摘要
        if (authorization == null || !MessageDigest.isEqual(this.expectedDigest, digest(authorization))) {
            // 认证失败不进入协议处理器
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            // 明确响应为普通JSON而非协议成功结果
            response.setContentType("application/json;charset=UTF-8");
            // 返回不包含敏感信息的错误
            response.getWriter().write("{\"error\":\"MCP authentication required\"}");
            // 终止当前请求
            return;
        }
        // 验证成功后交给SDK处理
        chain.doFilter(request, response);
    }

    // 将任意长度Token转换为固定长度摘要
    private static byte[] digest(String value) {
        try {
            // 使用JDK标准SHA-256实现
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            // JDK缺少标准算法时拒绝启动
            throw new IllegalStateException("JDK不支持SHA-256", exception);
        }
    }
}
