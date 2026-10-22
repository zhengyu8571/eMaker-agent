package com.tianji.gateway.filter;

import com.tianji.authsdk.gateway.util.AuthUtil;
import com.tianji.common.domain.R;
import com.tianji.common.domain.dto.LoginUserDTO;
import com.tianji.gateway.config.AuthProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.tianji.auth.common.constants.JwtConstants.*;

@Component
public class AccountAuthFilter implements GlobalFilter, Ordered {

    private final AuthUtil authUtil;
    private final AuthProperties authProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public AccountAuthFilter(AuthUtil authUtil, AuthProperties authProperties) {
        this.authUtil = authUtil;
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 清除客户端伪造的内部身份头，保留原Authorization
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            // 用户身份只能由网关校验后写入
            headers.remove(USER_HEADER);
            // 内部Token只能来自本次认证结果
            headers.remove(TOKEN_HEADER);
        }).build();
        // 将清理后的请求放入新的上下文
        ServerWebExchange forwardedExchange = exchange.mutate().request(request).build();
        // 获取请求方法
        String method = request.getMethod().name();
        // 获取请求路径
        String path = request.getPath().toString();
        // 拼接原权限匹配格式
        String antPath = method + ":" + path;

        // 免登录路径仍清除外部身份头
        if(isExcludePath(antPath)){
            // MCP的服务Token继续由下游过滤器校验
            return chain.filter(forwardedExchange);
        }

        // 读取原Authorization认证凭据
        List<String> authHeaders = request.getHeaders().get(AUTHORIZATION_HEADER);
        // 缺少凭据时沿用原匿名认证结果
        String token = authHeaders == null || authHeaders.isEmpty() ? "" : authHeaders.get(0);
        // 沿用原JWT解析与有效性校验
        R<LoginUserDTO> r = authUtil.parseToken(token);

        // 沿用原路径权限校验，拒绝时不进入下游
        authUtil.checkAuth(antPath, r);
        // 认证成功后才写入可信身份
        if (r.success()) {
            // 接住新上下文，不能丢弃build返回值
            forwardedExchange = forwardedExchange.mutate().request(builder -> builder.headers(headers -> {
                // 覆盖所有同名用户头，只保留验证出的用户
                headers.set(USER_HEADER, r.getData().getUserId().toString());
                // 覆盖内部Token头，不信任客户端提供的值
                headers.set(TOKEN_HEADER, token);
            })).build();
        }

        // 将处理后的上下文交给后续过滤器
        return chain.filter(forwardedExchange);
    }

    private boolean isExcludePath(String antPath) {
        for (String pathPattern : authProperties.getExcludePath()) {
            if(antPathMatcher.match(pathPattern, antPath)){
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
