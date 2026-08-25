package com.amz.aspect;

import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 角色权限校验切面（RBAC 写操作防护）。
 * <p>
 * 拦截所有标注 {@link RequireRole} 的 Controller 方法，
 * 校验 {@code UserContext.getRole()} 是否在允许集合内：
 * <ul>
 *   <li>命中 → 放行执行原方法；</li>
 *   <li>未命中 / role 缺失 → 返回 {@link Result#failure(String)}，方法不执行；</li>
 *   <li>切面内部异常按拒绝处理（fail-closed：权限组件故障不应放行写操作）。</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
public class RequireRoleAspect {

    @Around("@annotation(requireRole)")
    public Object check(ProceedingJoinPoint pjp, RequireRole requireRole) throws Throwable {
        Set<String> allowed = new HashSet<>(Arrays.asList(requireRole.value()));
        String role = null;
        try {
            role = UserContext.getRole();
        } catch (Exception e) {
            log.warn("RequireRole 读取角色上下文失败，按拒绝处理：{}", e.getMessage());
        }
        if (role == null || !allowed.contains(role)) {
            log.warn("RequireRole 拦截：method={} required={} actual={}",
                    pjp.getSignature().toShortString(),
                    Arrays.toString(requireRole.value()), role);
            return Result.failure("无权限执行该操作（需要角色：" + String.join("/", requireRole.value()) + "）");
        }
        return pjp.proceed();
    }
}
