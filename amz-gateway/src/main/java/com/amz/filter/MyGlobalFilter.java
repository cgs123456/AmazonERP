package com.amz.filter;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.amz.enums.ResponseEnum;
import com.amz.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局过滤器
 */
@Slf4j
@Configuration
public class MyGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1.获取请求和响应对象
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        // 2.判断是否是登录接口
        String path = request.getURI().getPath();
        log.info("请求接口为：{}", path);
        if (path.startsWith("/user/send") || path.startsWith("/user/verify") || path.equals("/note/getNoteList") || path.contains("/helper")) {
            log.info("请求接口放行");
            return chain.filter(exchange);
        }

        // 3.获取token
        String token = request.getHeaders().getFirst("token");

        // 4.判断token是否为空
        if (StringUtils.isBlank(token)) {
            response.setStatusCode(HttpStatus.valueOf(ResponseEnum.UNAUTHORIZED.getCode()));
            return response.setComplete();
        }

        // 5.判断token是否过期
        try {
            String userId = JwtUtil.parseToken(token);

            // 6.向请求头添加userId
            request.mutate().header("userId", userId);

            // 7. 多店铺权限：对 /shop/** /product/** /order/** 路径，校验并透传 shopId
            if (path.startsWith("/shop/") || path.startsWith("/product/") || path.startsWith("/order/")) {
                String shopId = request.getHeaders().getFirst("shopId");
                if (StringUtils.isBlank(shopId)) {
                    log.warn("店铺相关接口缺少 shopId header: {}", path);
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return response.setComplete();
                }
                // 透传 shopId 到下游服务
                request.mutate().header("shopId", shopId);
            }

            return chain.filter(exchange);
        } catch (TokenExpiredException | JWTDecodeException e2) {
            response.setStatusCode(HttpStatus.valueOf(ResponseEnum.UNAUTHORIZED.getCode()));
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
