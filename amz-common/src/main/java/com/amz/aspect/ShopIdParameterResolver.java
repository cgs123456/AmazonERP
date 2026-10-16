package com.amz.aspect;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 从 Controller 方法签名里解析「本次操作面向哪个店铺」。
 * <p>
 * <b>存在动因</b>：店铺上下文有两个来源——请求头 {@code shopId}（客户端当前选中的店）
 * 和方法参数（这次操作真正要动的店）。两者可能不一致：客户端 header 是店 A，
 * 路径参数是店 B。{@link ShopIdGuardAspect} 一直在按方法参数校验（正确），
 * 而角色校验如果只看 header，就会出现「拿店 A 的 OPERATOR 去写店 B」的错配洞。
 * 所以两边必须用同一套解析，而不是各写一份。
 * <p>
 * 解析口径与 {@link ShopIdGuardAspect} 原实现逐字一致：
 * <ul>
 *   <li>只认类型为 {@code Long}/{@code long}、名为 {@code shopId} 的参数；</li>
 *   <li>优先 {@link PathVariable}（RESTful 路径参数更常见），其次 {@link RequestParam}；</li>
 *   <li>注解显式给了名字就按显式名匹配（{@code @PathVariable("shopId") Long tenantId}
 *       这种参数名与语义名不一致的写法要认出来）。</li>
 * </ul>
 * 解析不到返回 {@code null}——调用方各自决定怎么降级（店铺校验跳过、角色校验回落全局角色）。
 */
public final class ShopIdParameterResolver {

    private static final String SHOP_ID_PARAM = "shopId";

    private static final ParameterNameDiscoverer PARAM_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    private ShopIdParameterResolver() {
    }

    /**
     * 解析方法签名中名为 {@code shopId} 的参数值。
     *
     * @param method 目标方法
     * @param args   本次调用的实参
     * @return shopId 值；未找到返回 {@code null}
     */
    public static Long resolve(Method method, Object[] args) {
        if (method == null) {
            return null;
        }
        Parameter[] params = method.getParameters();
        String[] paramNames = PARAM_NAME_DISCOVERER.getParameterNames(method);
        // 优先 @PathVariable（RESTful 路径参数更常见），其次 @RequestParam
        Long value = match(params, args, paramNames, PathVariable.class);
        if (value != null) {
            return value;
        }
        return match(params, args, paramNames, RequestParam.class);
    }

    private static Long match(Parameter[] params, Object[] args, String[] paramNames,
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
