package com.amz.aspect;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 Controller 方法签名里解析「本次操作面向哪个店铺」。
 * <p>
 * <b>存在动因</b>：店铺上下文有两个来源——请求头 {@code shopId}（客户端当前选中的店）
 * 和方法参数（这次操作真正要动的店）。两者可能不一致：客户端 header 是店 A，
 * 路径参数是店 B。{@link ShopIdGuardAspect} 一直在按方法参数校验（正确），
 * 而角色校验如果只看 header，就会出现「拿店 A 的 OPERATOR 去写店 B」的错配洞。
 * 所以两边必须用同一套解析，而不是各写一份。
 * <p>
 * 解析口径：
 * <ul>
 *   <li>只认类型为 {@code Long}/{@code long}、名为 {@code shopId} 的参数；</li>
 *   <li>优先 {@link PathVariable}（RESTful 路径参数更常见），其次 {@link RequestParam}，
 *       最后 {@link RequestBody} 实体里的 {@code shopId} 属性；</li>
 *   <li>注解显式给了名字就按显式名匹配（{@code @PathVariable("shopId") Long tenantId}
 *       这种参数名与语义名不一致的写法要认出来）。</li>
 * </ul>
 *
 * <h2>为什么必须读请求体（这是一条真实越权路径）</h2>
 * 网关只校验请求<b>头</b> {@code shopId} 属于当前用户，而请求头是「客户端当前选中的店」；
 * 请求体里的 shopId 是另一个完全独立的值。若切面只认路径/查询参数，那么把请求头设成自己的店
 * （过网关）、请求体填受害者的店，切面就解析不到 shopId 而走「兼容放行」——
 * <b>两层防线同时让开</b>。本仓曾有 32 个 {@code @ShopScoped} 写端点的 shopId 只在请求体里；
 * 其中 amz-service-product 的 5 个端点连 service 层行级校验都没有，
 * 等于任何已登录用户（含 VIEWER）都能往任意店铺写入。
 * <p>
 * 读请求体是安全的：实体类型是编译期确定的，{@code shopId} 是有名字段，
 * 不存在「从无结构的 Map 里猜 key」这种把无关字段误当店铺的风险。
 *
 * <h2>刻意不支持 {@code @RequestBody Map}</h2>
 * {@code Map} 没有声明字段，只能按 key 猜——把业务 Map 里恰好叫 shopId 的键当租户标识，
 * 或反之漏掉真正承载租户的键，两个方向都会出错。这类端点必须自行校验
 * （先例：{@code ProductController.copyListing} 显式调用 {@code isShopAllowedStrict}）。
 * <p>
 * 解析不到返回 {@code null}——调用方各自决定怎么降级（店铺校验跳过、角色校验回落全局角色）。
 */
@Slf4j
public final class ShopIdParameterResolver {

    private static final String SHOP_ID_PARAM = "shopId";

    private static final ParameterNameDiscoverer PARAM_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    /**
     * 请求体类型 → 读取 shopId 的方式；{@code null} 表示该类型没有 shopId 属性。
     * <p>
     * 缓存原因：本方法在每次 {@code @ShopScoped}/{@code @RequireRole} 调用上都会跑，
     * 而反射查找 getter/字段是每请求的固定开销。类型集合是有限且稳定的，缓存一次即可。
     * 用 {@code ConcurrentHashMap} 且允许 null 值（{@code containsKey} 区分「没查到」与「查过没有」）。
     */
    private static final Map<Class<?>, Object> SHOP_ID_READER_CACHE = new ConcurrentHashMap<>();

    /** 缓存哨兵：该类型确认没有 shopId 属性（{@code ConcurrentHashMap} 不允许 null 值）。 */
    private static final Object NO_SHOP_ID = new Object();

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
        value = match(params, args, paramNames, RequestParam.class);
        if (value != null) {
            return value;
        }
        return matchBody(params, args);
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

    /**
     * 从 {@code @RequestBody} 实体里读 {@code shopId} 属性。
     * <p>
     * 只处理声明了字段的具体类型；{@code Map} 刻意跳过（见类注释：无声明字段只能靠猜 key）。
     */
    private static Long matchBody(Parameter[] params, Object[] args) {
        for (int i = 0; i < params.length && i < args.length; i++) {
            if (params[i].getAnnotation(RequestBody.class) == null) {
                continue;
            }
            Object body = args[i];
            if (body == null) {
                continue;
            }
            Long value = readShopId(body);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long readShopId(Object body) {
        Class<?> type = body.getClass();
        Object reader = SHOP_ID_READER_CACHE.computeIfAbsent(type, ShopIdParameterResolver::findReader);
        if (reader == NO_SHOP_ID || reader == null) {
            return null;
        }
        try {
            Object raw = ((ShopIdReader) reader).read(body);
            return toLong(raw);
        } catch (Exception e) {
            // 读属性失败不等于「没有店铺」——但这里返回 null 会让上层走兼容放行，
            // 所以必须留日志，否则越权会静默发生。
            log.warn("ShopIdParameterResolver 读取请求体 shopId 失败，按解析不到处理：type={} msg={}",
                    type.getName(), e.getMessage());
            return null;
        }
    }

    /** 能抛受检异常的读取器（反射的 invoke/get 都会抛，不能直接用 {@code Function}）。 */
    @FunctionalInterface
    private interface ShopIdReader {
        Object read(Object target) throws Exception;
    }

    /**
     * 定位该类型读取 shopId 的方式：优先无参 getter（Lombok {@code @Data}/{@code @Getter}
     * 生成的形态，也兼容 record 的访问器），退化到直接读字段
     * （少数实体没加 Lombok，或字段是包级可见）。
     */
    private static Object findReader(Class<?> type) {
        if (Map.class.isAssignableFrom(type)) {
            return NO_SHOP_ID;
        }
        try {
            Method getter = type.getMethod("getShopId");
            if (Long.class.isAssignableFrom(getter.getReturnType())
                    || Long.TYPE.equals(getter.getReturnType())) {
                return (ShopIdReader) getter::invoke;
            }
            // 返回类型不是 Long：可能是 record 的 shopId() 或写错了类型，都当没有处理
        } catch (NoSuchMethodException ignore) {
            // 没有 getShopId()，试试字段
        }
        try {
            Field field = type.getDeclaredField("shopId");
            if (!Long.class.isAssignableFrom(field.getType()) && !Long.TYPE.equals(field.getType())) {
                return NO_SHOP_ID;
            }
            field.setAccessible(true);
            return (ShopIdReader) field::get;
        } catch (NoSuchFieldException ignore) {
            return NO_SHOP_ID;
        }
    }

    /**
     * 把请求体里读出的值收敛为 Long。
     * <p>
     * 只接受已经装箱的 {@code Long}（实体字段类型就是 Long）。其它类型一律当作「没有店铺」：
     * 若这里是宽松的「能转就转」，一个业务上表示别的东西、恰好叫 shopId 的字符串字段
     * 就会变成租户标识——宁可解析不到（上层按既有降级走），也不猜。
     */
    private static Long toLong(Object raw) {
        return raw instanceof Long value ? value : null;
    }
}
