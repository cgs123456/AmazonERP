package com.amz.filter;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.amz.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
            "/user/refresh",
            // /actuator/** 为 k8s 存活/就绪探针端点（kubelet 请求不携带 JWT），必须放行，
            // 否则网关自身探针恒返回 401 导致 Pod 永远 NotReady。
            // 网关未配置 /actuator/** 的 lb 路由，故不会转发到下游业务服务。
            "/actuator",
            // 平台回调（Temu/TikTok/Shein）不带用户 JWT，鉴权靠端点自身的 HMAC-SHA256
            // 验签（X-Signature fail-closed，无效签名不落库）。必须与服务层
            // BaseAuthInterceptor.WHITE_LIST 同步（AuthWhitelistParityContractTest）。
            "/multiplatform/webhook"
    );

    /**
     * 文档端点白名单（Swagger UI / OpenAPI）。默认关闭，避免生产环境暴露 API 结构；
     * 本地开发通过 application-local.yml 或 GATEWAY_DOCS_ENABLED=true 显式开启。
     */
    private static final List<String> DOCS_WHITELIST = List.of(
            "/swagger-ui",
            "/v3/api-docs"
    );

    @Autowired
    private JwtUtil jwtUtil;

    /** 是否放行文档端点，生产默认 false。 */
    @Value("${amz.gateway.docs.enabled:false}")
    private boolean docsEnabled;

    private static boolean isInternalPath(String path) {
        return "/internal".equals(path) || path.startsWith("/internal/");
    }

    /** 判断路径是否命中白名单：path 等于白名单项，或以 白名单项 + "/" 开头 */
    private boolean isWhiteListed(String path) {
        for (String prefix : WHITE_LIST) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        if (docsEnabled) {
            for (String prefix : DOCS_WHITELIST) {
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    return true;
                }
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
        if (log.isDebugEnabled()) {
            log.debug("请求接口为：{}", path);
        }
        // 内部服务端点只能由服务间令牌直达，禁止经公网网关访问。
        if (isInternalPath(path)) {
            log.warn("拒绝经网关访问内部端点: {}", path);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return response.setComplete();
        }
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 3.获取token
        String token = request.getHeaders().getFirst("token");

        // 4.判断token是否为空
        if (StringUtils.isBlank(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }

        // 5.校验token并解析
        try {
            String userId = jwtUtil.parseToken(token);
            List<Long> shops = jwtUtil.parseTokenShops(token);

            // 6. 重写请求头：
            //    a) 先剥离外部可能伪造的 userId 头，再写入从 JWT 解析出的权威值；
            //       （旧实现 mutate() 结果被丢弃、且从不剥离入站 userId——一旦下游重新注册
            //        信任该头的拦截器即构成完整身份伪造链路）
            ServerHttpRequest.Builder headerBuilder = request.mutate()
                    .headers(h -> h.remove("userId"))
                    .header("userId", userId);

            // 7. 多店铺越权校验：只要请求携带 shopId header 即校验其是否属于当前用户授权店铺，
            //    覆盖所有多租户业务路径；白名单路径已在上方放行。
            String shopId = request.getHeaders().getFirst("shopId");
            if (StringUtils.isNotBlank(shopId)) {
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
                // 校验通过后剥离旧值再写入，保持单值语义并透传到下游服务
                headerBuilder.headers(h -> h.remove("shopId"))
                        .header("shopId", shopId);
            }

            // 8. 必须转发"变异后"的 exchange：mutate() 返回新对象，
            //    直接透传原 exchange 会丢失所有头修改
            ServerHttpRequest mutatedRequest = headerBuilder.build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (TokenExpiredException | JWTDecodeException e2) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
