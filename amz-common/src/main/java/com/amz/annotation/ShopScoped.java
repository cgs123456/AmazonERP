package com.amz.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多租户 shopId 二次校验注解（下游防御）。
 * <p>
 * 标注在 Controller 方法上，由 {@code com.amz.aspect.ShopIdGuardAspect} 拦截：
 * 校验方法中 {@code @RequestParam Long shopId} 参数值是否在当前用户授权的
 * {@link com.amz.context.UserContext#getShops()} 店铺列表内；不在则拒绝。
 * <p>
 * 这是网关 {@code MyGlobalFilter} 之后的下游二次防御。当 UserContext 无 shops
 * （白名单 / 内部调用 / 未走 BaseAuthInterceptor）时跳过校验，兼容放行。
 * <p>
 * 切面异常一律吞掉并放行（仅记 warn 日志），避免阻断业务。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ShopScoped {
}
