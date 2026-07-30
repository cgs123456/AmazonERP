package com.amz.aspect;

import com.amz.annotation.ShopScoped;
import com.amz.context.UserContext;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.annotation.Annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

/**
 * 多租户 shopId 二次校验 AOP 切面（下游防御）。
 * <p>
 * 拦截标注 {@link ShopScoped} 的 Controller 方法，定位其中类型为 {@code Long}、
 * 名为 {@code shopId} 且带 {@link RequestParam} 或 {@link PathVariable} 的参数，校验其值是否在当前用户
 * 授权的 {@link UserContext#getShops()} 店铺列表内。
 * <ul>
 *   <li>UserContext 无 shops（白名单 / 内部调用 / 未走 BaseAuthInterceptor）→ 跳过，放行；</li>
 *   <li>shopId 在授权列表内 → 放行；</li>
 *   <li>shopId 不在授权列表内 → 返回 {@link Result#failure(String)} 拦截；</li>
 *   <li>未找到 shopId 参数 → 跳过，放行。</li>
 * </ul>
 * <p>
 * 安全降级：切面任何异常均被吞掉并放行（仅记 warn 日志），避免阻断业务。
 * 这是网关 {@code MyGlobalFilter} 之后的下游二次防御。
 */
@Slf4j
@Aspect
@Component
public class ShopIdGuardAspect {

    private static final String SHOP_ID_PARAM = "shopId";

    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(com.amz.annotation.ShopScoped)")
    public Object guard(ProceedingJoinPoint pjp) throws Throwable {
        try {
            List<Long> shops = UserContext.getShops();
            // UserContext 无 shops（白名单 / 内部调用 / 未走鉴权拦截器），跳过校验，兼容放行
            if (shops == null || shops.isEmpty()) {
                return pjp.proceed();
            }
            Long shopIdValue = resolveShopId(pjp);
            if (shopIdValue == null) {
                // 未找到 shopId 参数，跳过
                return pjp.proceed();
            }
            if (!shops.contains(shopIdValue)) {
                log.warn("ShopIdGuardAspect: shopId 越权拦截，userId={}, shopId={}, 授权shops={}",
                        UserContext.getUserId(), shopIdValue, shops);
                return Result.failure("shopId 不在授权范围");
            }
        } catch (Exception e) {
            // 切面异常吞掉并放行，避免阻断业务
            log.warn("ShopIdGuardAspect 校验异常，放行: {}", e.getMessage());
        }
        return pjp.proceed();
    }

    /**
     * 解析方法签名中名为 {@code shopId}、类型为 {@code Long} 的参数值。
     * 优先匹配 {@link PathVariable}，其次匹配 {@link RequestParam}。
     *
     * @return shopId 参数值；未找到返回 null
     */
    private Long resolveShopId(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Parameter[] params = method.getParameters();
        Object[] args = pjp.getArgs();
        String[] paramNames = paramNameDiscoverer.getParameterNames(method);
        // 优先 @PathVariable（RESTful 路径参数更常见），其次 @RequestParam
        Long value = matchShopId(params, args, paramNames, PathVariable.class);
        if (value != null) {
            return value;
        }
        return matchShopId(params, args, paramNames, RequestParam.class);
    }

    /**
     * 按指定注解类型匹配名为 {@code shopId}、类型为 {@code Long} 的参数值。
     */
    private Long matchShopId(Parameter[] params, Object[] args, String[] paramNames,
                             Class<? extends Annotation> annType) {
        for (int i = 0; i < params.length; i++) {
            if (params[i].getAnnotation(annType) == null) {
                continue;
            }
            Class<?> type = params[i].getType();
            if (type != Long.class && type != Long.TYPE) {
                continue;
            }
            String name = (paramNames != null && i < paramNames.length) ? paramNames[i] : params[i].getName();
            if (SHOP_ID_PARAM.equals(name) && i < args.length && args[i] instanceof Long) {
                return (Long) args[i];
            }
        }
        return null;
    }
}
