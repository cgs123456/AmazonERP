package com.amz.interceptor;

import com.amz.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 自定义拦截器
 */
@Slf4j
public class MyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        log.info("前置拦截器");
        // 1. 获取用户id
        String userId = request.getHeader("userId");
        if (userId == null) {
            response.setStatus(401);
            return false;
        }
        // 2. 设置用户id
        log.info("用户id为：{}", userId);
        UserContext.setUserId(Integer.valueOf(userId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        log.info("后置拦截器");
        UserContext.clear();
    }
}
