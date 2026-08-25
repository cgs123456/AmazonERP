package com.amz.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口角色要求注解（RBAC 写操作防护）。
 * <p>
 * 标注在 Controller 方法上，声明允许调用该接口的最小角色集合。
 * 切面 {@code RequireRoleAspect} 在方法执行前校验
 * {@code UserContext.getRole()}（由 BaseAuthInterceptor 从 JWT role claim 解析）：
 * <ul>
 *   <li>角色不在允许集合 → 返回 {@code Result.failure} 拦截（HTTP 层仍 200，
 *       与现有 Result 契约一致）；</li>
 *   <li>role 为 null（未走鉴权拦截器的内部调用/白名单）→ 同样拦截，
 *       避免无角色上下文的请求绕过；</li>
 *   <li>未标注该注解的端点不受影响。</li>
 * </ul>
 * 内置角色约定（与 JwtUtil 默认签发口径一致）：{@code VIEWER / OPERATOR / ADMIN}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色集合，任一命中即放行。 */
    String[] value();
}
