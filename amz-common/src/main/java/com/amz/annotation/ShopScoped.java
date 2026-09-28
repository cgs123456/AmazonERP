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
 * 这是网关 {@code MyGlobalFilter} 之后的下游二次防御。已认证非 ADMIN 用户缺少 shops claim 时
 * 必须拒绝；无 userId 的白名单 / 内部调用保持兼容放行。
 * <p>
 * 切面自身异常按 fail-closed 拒绝，不静默放行；避免权限组件故障扩大为越权窗口。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ShopScoped {
}
