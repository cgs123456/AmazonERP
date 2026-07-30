package com.amz.interceptor;

import com.amz.context.UserContext;
import com.amz.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 通用鉴权拦截器：校验 {@code token} 请求头中的 JWT，而非裸信 {@code userId}。
 * 各微服务在 WebMvcConfig 中通过 {@code new BaseAuthInterceptor(jwtUtil)} 注册即可复用。
 * 不加 @Component，避免被响应式服务（网关/消息）扫描时引入 servlet API 依赖问题。
 */
@Slf4j
public class BaseAuthInterceptor implements HandlerInterceptor {

    /** 免鉴权路径白名单（与网关保持一致） */
    private static final List<String> WHITE_LIST = List.of(
            "/user/send",
            "/user/verify",
            // /internal/** 仅供服务间内部 Feign 调用（不带用户 JWT），网关未配置该路径前缀路由，不对外暴露
            "/internal"
    );

    private final JwtUtil jwtUtil;

    public BaseAuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    private boolean isWhiteListed(String path) {
        for (String prefix : WHITE_LIST) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        // 白名单直接放行
        if (isWhiteListed(path)) {
            return true;
        }
        // 校验 token header（不再裸信 userId header）
        String token = request.getHeader("token");
        if (token == null || token.isBlank()) {
            log.warn("请求缺少 token，路径: {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        try {
            String userId = jwtUtil.parseToken(token);
            log.info("鉴权通过，用户id为：{}", userId);
            UserContext.setUserId(Integer.valueOf(userId));
            // 解析角色 claim（未携带时由 JwtUtil 默认为 VIEWER），供字段级权限切面使用
            String role = jwtUtil.parseTokenRole(token);
            UserContext.setRole(role);
            // 校验并设置 shopId（多店铺隔离）
            List<Long> shops = jwtUtil.parseTokenShops(token);
            // 写入授权店铺列表，供下游 ShopIdGuardAspect 二次校验使用
            UserContext.setShops(shops);
            String shopId = request.getHeader("shopId");
            if (shopId != null && !shopId.isBlank()) {
                Long shopIdVal = Long.valueOf(shopId);
                if (!shops.contains(shopIdVal)) {
                    log.warn("shopId 越权: userId={}, shopId={}", userId, shopId);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return false;
                }
                UserContext.setShopId(shopIdVal);
            }
            return true;
        } catch (Exception e) {
            log.warn("token 校验失败: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
