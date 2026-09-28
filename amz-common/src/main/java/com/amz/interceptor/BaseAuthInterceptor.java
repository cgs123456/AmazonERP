package com.amz.interceptor;

import com.amz.annotation.InternalServiceAccess;
import com.amz.context.UserContext;
import com.amz.security.InternalServiceTokenService;
import com.amz.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * 通用鉴权拦截器：校验 {@code token} 请求头中的用户 JWT，而非裸信 {@code userId}。
 * <p>
 * {@code /internal/**} 不再作为免鉴权白名单，必须携带有效内部服务令牌。
 * 普通业务端点若声明 {@link InternalServiceAccess}，允许“用户 JWT 或白名单服务令牌”
 * 二选一；未声明的普通端点只接受用户 JWT。
 * <p>
 * 单参数构造器保留给现有单元测试；生产注册必须传入 {@link InternalServiceTokenService}，
 * 否则内部路径与受信服务调用 fail-closed。
 */
@Slf4j
public class BaseAuthInterceptor implements HandlerInterceptor {

    /** 免鉴权路径白名单（与网关保持一致） */
    private static final List<String> WHITE_LIST = List.of(
            "/user/send",
            "/user/verify",
            // /actuator/** 为 k8s 存活/就绪探针端点（kubelet 请求不携带 JWT），
            // 必须放行，否则探针恒返回 401 导致 Pod 永远 NotReady。
            // 该端点仅在集群内 ClusterIP 暴露，网关未配置对应路由，不对外暴露。
            "/actuator"
    );

    private final JwtUtil jwtUtil;
    private final InternalServiceTokenService internalServiceTokenService;

    public BaseAuthInterceptor(JwtUtil jwtUtil) {
        this(jwtUtil, null);
    }

    public BaseAuthInterceptor(JwtUtil jwtUtil, InternalServiceTokenService internalServiceTokenService) {
        this.jwtUtil = jwtUtil;
        this.internalServiceTokenService = internalServiceTokenService;
    }

    private boolean isWhiteListed(String path) {
        for (String prefix : WHITE_LIST) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInternalPath(String path) {
        return "/internal".equals(path) || path.startsWith("/internal/");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (isInternalPath(path)) {
            return authenticateInternalService(request, response, path, handler);
        }
        // 白名单直接放行
        if (isWhiteListed(path)) {
            return true;
        }

        String userToken = request.getHeader("token");
        String serviceToken = request.getHeader(InternalServiceTokenService.HEADER_NAME);
        boolean hasUserToken = userToken != null && !userToken.isBlank();
        boolean hasServiceToken = serviceToken != null && !serviceToken.isBlank();

        // 普通业务端点的双信任边界：只有显式声明 InternalServiceAccess 才允许服务令牌。
        // 用户 JWT 优先；即使同时带服务令牌，也不会跳过用户角色/店铺校验。
        if (!hasUserToken && hasServiceToken && hasInternalServiceAccess(handler)) {
            return authenticateInternalService(request, response, path, handler);
        }

        return authenticateUser(request, response, path);
    }

    private boolean authenticateUser(
            HttpServletRequest request, HttpServletResponse response, String path) {
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

    private boolean hasInternalServiceAccess(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        return findInternalServiceAccess(handlerMethod) != null;
    }

    private boolean authenticateInternalService(
            HttpServletRequest request, HttpServletResponse response, String path, Object handler) {
        if (internalServiceTokenService == null) {
            log.error("内部服务令牌校验器未装配，拒绝访问: {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        String serviceToken = request.getHeader(InternalServiceTokenService.HEADER_NAME);
        if (serviceToken == null || serviceToken.isBlank()) {
            log.warn("内部请求缺少服务令牌，路径: {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        try {
            String serviceName = internalServiceTokenService.verify(serviceToken);
            if (!isInternalServiceAllowed(handler, serviceName, path, response)) {
                return false;
            }
            UserContext.setInternalService(serviceName);
            log.info("内部服务鉴权通过，service={}, path={}", serviceName, path);
            return true;
        } catch (Exception e) {
            log.warn("内部服务令牌校验失败，路径={}: {}", path, e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    private boolean isInternalServiceAllowed(
            Object handler, String serviceName, String path, HttpServletResponse response) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            log.warn("内部端点未解析到 HandlerMethod，按拒绝处理：service={}, path={}", serviceName, path);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        InternalServiceAccess access = findInternalServiceAccess(handlerMethod);
        if (access == null || access.value().length == 0) {
            log.warn("端点未声明调用方白名单，按拒绝处理：service={}, path={}", serviceName, path);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        boolean allowed = Arrays.stream(access.value()).anyMatch(serviceName::equals);
        if (!allowed) {
            log.warn("服务身份无权访问端点：service={}, path={}, allowed={}",
                    serviceName, path, Arrays.toString(access.value()));
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }

    private InternalServiceAccess findInternalServiceAccess(HandlerMethod handlerMethod) {
        InternalServiceAccess access = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), InternalServiceAccess.class);
        if (access == null) {
            access = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), InternalServiceAccess.class);
        }
        return access;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
