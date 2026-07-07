package com.amz.interceptor;

import com.amz.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 基础拦截器 - 提取公共拦截逻辑
 */
@Slf4j
public class BaseInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        log.info("前置拦截器");
        // 1. 获取用户id
        String userId = request.getHeader("userId");
        if (userId != null) {
            // 2. 设置用户id
            log.info("用户id为：{}", userId);
            UserContext.setUserId(Integer.valueOf(userId));
        } else {
            // userId 为 null 时返回 401
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        // 3. 获取 shopId（多店铺隔离，可选）
        String shopId = request.getHeader("shopId");
        if (shopId != null && !shopId.isBlank()) {
            UserContext.setShopId(Long.valueOf(shopId));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        log.info("后置拦截器");
        UserContext.clear();
    }
}
