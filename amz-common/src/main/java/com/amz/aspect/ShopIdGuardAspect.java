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
 *   <li>已认证非 ADMIN 用户无 shops → fail-closed；无 userId 的白名单 / 内部调用 → 兼容放行；</li>
 *   <li>shopId 在授权列表内 → 放行；</li>
 *   <li>shopId 不在授权列表内 → 返回 {@link Result#failure(String)} 拦截；</li>
 *   <li>未找到 shopId 参数 → 兼容放行；新写接口必须显式传 shopId，不能依赖本切面兜底。</li>
 * </ul>
 * <p>
 * 安全降级：切面自身异常按 fail-closed 拒绝，不静默放行，避免权限组件故障扩大为越权窗口。
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
            // 无用户上下文的内部调用继续兼容放行；但已经过 BaseAuthInterceptor 的
            // 外部请求如果没有任何 shops 授权，绝不能因为“列表为空”被解释为“无限制”。
            // ADMIN 保留全局管理语义，非 ADMIN 必须 fail-closed。
            if (shops == null || shops.isEmpty()) {
                if (UserContext.getUserId() != null && !isAdmin()) {
                    log.warn("ShopIdGuardAspect: 已认证用户无任何店铺授权，拒绝访问，userId={}, role={}",
                            UserContext.getUserId(), UserContext.getRole());
                    return Result.failure("当前用户没有任何店铺授权");
                }
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
            // fail-closed：租户防护组件自身异常时拒绝而非放行。
            // 旧实现吞异常放行——上游任何导致 shops 缺失/切面报错的 bug
            // 都会静默关闭多租户校验，构成越权窗口。此处拦截并返回失败，
            // 业务方可依据日志快速定位切面异常根因。
            log.error("ShopIdGuardAspect 校验异常，按拒绝处理（fail-closed） - class: {}, msg: {}",
                    e.getClass().getName(), e.getMessage());
            return Result.failure("店铺权限校验异常，请稍后重试");
        }
        return pjp.proceed();
    }

    private static boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(UserContext.getRole());
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
            String name = annotationName(params[i].getAnnotation(annType));
            if (name == null || name.isBlank()) {
                name = (paramNames != null && i < paramNames.length) ? paramNames[i] : params[i].getName();
            }
            if (SHOP_ID_PARAM.equals(name) && i < args.length && args[i] instanceof Long) {
                return (Long) args[i];
            }
        }
        return null;
    }

    private static String annotationName(Annotation annotation) {
        if (annotation instanceof PathVariable pathVariable) {
            return pathVariable.value();
        }
        if (annotation instanceof RequestParam requestParam) {
            return requestParam.name().isBlank() ? requestParam.value() : requestParam.name();
        }
        return null;
    }
}
