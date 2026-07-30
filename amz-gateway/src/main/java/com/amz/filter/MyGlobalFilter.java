package com.amz.filter;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.amz.enums.ResponseEnum;
import com.amz.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 全局过滤器
 */
@Slf4j
@Configuration
public class MyGlobalFilter implements GlobalFilter, Ordered {

    /** 免鉴权路径白名单（精确前缀匹配，避免 contains 误放行） */
    private static final List<String> WHITE_LIST = List.of(
            "/user/send",
            "/user/verify",
            // /internal/** 仅供服务间内部 Feign 调用（不带用户 JWT），网关未配置该路径前缀路由，不对外暴露
            "/internal"
    );

    @Autowired
    private JwtUtil jwtUtil;

    /** 判断路径是否命中白名单：path 等于白名单项，或以 白名单项 + "/" 开头 */
    private boolean isWhiteListed(String path) {
        for (String prefix : WHITE_LIST) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1.获取请求和响应对象
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        // 2.判断是否是白名单接口
        String path = request.getURI().getPath();
        log.info("请求接口为：{}", path);
        if (isWhiteListed(path)) {
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

        // 5.校验token并解析
        try {
            String userId = jwtUtil.parseToken(token);
            List<Long> shops = jwtUtil.parseTokenShops(token);

            // 6.向请求头添加userId
            request.mutate().header("userId", userId);

            // 7. 多店铺越权校验：只要请求携带 shopId header 即校验其是否属于当前用户授权店铺，
            //    覆盖所有多租户业务路径（/finance/ /logistics/ /procurement/ /customer/ /ops/ /ad/
            //    /report/ /warehouse/ /multiplatform/ /spapi/ /ai/ /selection/ /shop/ /product/ /order/ 等），
            //    白名单路径（/user/send /user/verify /internal）已在上方放行。
            String shopId = request.getHeaders().getFirst("shopId");
            if (StringUtils.isNotBlank(shopId)) {
                // 校验 shopId 是否在用户授权的 shops 列表中
                Long shopIdVal;
                try {
                    shopIdVal = Long.valueOf(shopId);
                } catch (NumberFormatException nfe) {
                    log.warn("shopId 格式非法: {}", shopId);
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return response.setComplete();
                }
                if (!shops.contains(shopIdVal)) {
                    log.warn("shopId 越权访问: userId={}, shopId={}, path={}", userId, shopId, path);
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
