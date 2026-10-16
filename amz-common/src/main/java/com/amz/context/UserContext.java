package com.amz.context;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户线程变量（多店铺 shopId + 角色 role + 授权店铺列表 shops + 可信服务身份）。
 */
@Slf4j
public class UserContext {
    public static final ThreadLocal<Integer> userThreadLocal = new ThreadLocal<>();
    public static final ThreadLocal<Long> shopThreadLocal = new ThreadLocal<>();
    /** 当前用户角色代码：ADMIN / OPERATOR / VIEWER，由 BaseAuthInterceptor 从 JWT 解析注入。 */
    public static final ThreadLocal<String> roleThreadLocal = new ThreadLocal<>();
    /** 当前用户授权访问的店铺 id 列表，由 BaseAuthInterceptor 从 JWT shops claim 解析注入。 */
    public static final ThreadLocal<List<Long>> shopsThreadLocal = new ThreadLocal<>();
    /**
     * 店铺级角色映射（shopId → ADMIN/OPERATOR/VIEWER），由 BaseAuthInterceptor 从 JWT
     * {@code shop_roles} claim 解析注入；未绑定的店铺不出现在这里。
     */
    public static final ThreadLocal<Map<Long, String>> shopRolesThreadLocal = new ThreadLocal<>();
    /** 当前可信服务身份，仅在服务令牌校验通过并命中 @InternalServiceAccess 白名单后写入。 */
    public static final ThreadLocal<String> internalServiceThreadLocal = new ThreadLocal<>();

    /** 「无店铺列表即放行」分支的命中计数，键为来源标识（见 recordNoListGrant）。 */
    private static final Map<String, Long> NO_LIST_GRANT_HITS = new ConcurrentHashMap<>();

    public static void setUserId(Integer userId) {
        userThreadLocal.set(userId);
    }

    public static Integer getUserId() {
        return userThreadLocal.get();
    }

    public static void setShopId(Long shopId) {
        shopThreadLocal.set(shopId);
    }

    public static Long getShopId() {
        return shopThreadLocal.get();
    }

    public static void setRole(String role) {
        roleThreadLocal.set(role);
    }

    /**
     * 获取当前用户角色代码。未注入时返回 null（调用方需自行降级处理）。
     */
    public static String getRole() {
        return roleThreadLocal.get();
    }

    /**
     * 设置当前用户授权访问的店铺 id 列表。
     */
    public static void setShops(List<Long> shops) {
        shopsThreadLocal.set(shops);
    }

    /**
     * 获取当前用户授权访问的店铺 id 列表。未注入时返回 null（调用方需自行降级处理）。
     */
    public static List<Long> getShops() {
        return shopsThreadLocal.get();
    }

    /**
     * 设置店铺级角色映射（shopId → 角色）。
     */
    public static void setShopRoles(Map<Long, String> shopRoles) {
        shopRolesThreadLocal.set(shopRoles);
    }

    /**
     * 获取店铺级角色映射；未注入时返回 null。
     */
    public static Map<Long, String> getShopRoles() {
        return shopRolesThreadLocal.get();
    }

    /**
     * 解析「本次操作」应当使用的角色——**店铺级优先，全局兜底**。
     * <p>
     * 这是店铺级角色存在的意义所在：同一个用户可以在店 A 是 OPERATOR、在店 B 是 VIEWER。
     * 判定顺序：
     * <ol>
     *   <li>{@code shopId} 非空且 {@code shop_roles} 里有该店 → 用店铺角色；</li>
     *   <li>否则 → 回落全局角色 {@link #getRole()}（兼容未绑定的店铺、以及所有
     *       「没有店铺上下文」的调用，如定时任务与跨店管理端点）。</li>
     * </ol>
     * <b>为什么不是「店铺角色缺失就拒绝」</b>：{@code shop_roles} 是后加的 claim，
     * 存量 JWT 里没有它。若缺失即拒绝，上线瞬间所有已签发 token 的写操作全部失效，
     * 直到用户重新登录。回落全局角色是「老 token 行为不变、新 token 精细生效」的渐进路径，
     * 且不影响安全性——回落到的仍是后端权威签发的角色，不是前端传入的值。
     * <p>
     * 调用方传 {@code null} 店铺时**必然**得到全局角色：能解析出店铺的调用点应当显式传入，
     * 解析不到才传 null，此时没有比全局角色更准确的信息可用。
     */
    public static String resolveRole(Long shopId) {
        if (shopId != null) {
            Map<Long, String> byShop = getShopRoles();
            if (byShop != null) {
                String shopRole = byShop.get(shopId);
                if (shopRole != null && !shopRole.isBlank()) {
                    return shopRole;
                }
            }
        }
        return getRole();
    }

    /**
     * 设置当前内部服务身份。该值只能由服务令牌校验成功后写入。
     */
    public static void setInternalService(String serviceName) {
        internalServiceThreadLocal.set(serviceName);
    }

    /**
     * 获取当前内部服务身份；普通用户请求返回 null。
     */
    public static String getInternalService() {
        return internalServiceThreadLocal.get();
    }

    /**
     * 兼容旧调用方的店铺校验。
     * <p>
     * 有用户身份时委托严格校验；无用户身份且 shops 为空时放行，以兼容定时任务和
     * 白名单内部调用。公网 Controller 不应依赖本方法，应使用
     * {@link #isShopAllowedStrict(Long)} 或
     * {@link #isShopAllowedByUserOrTrustedService(Long)}。
     */
    public static boolean isShopAllowed(Long shopId) {
        if (getUserId() != null) {
            return isShopAllowedStrict(shopId);
        }
        List<Long> shops = getShops();
        if (shops == null || shops.isEmpty()) {
            recordNoListGrant();
            return true;
        }
        return shopId != null && shops.contains(shopId);
    }

    /**
     * 「因为没有店铺限制，所以放行」这一分支的计数与首次告警。
     * <p>
     * 存在动因：该分支同时被定时任务、白名单内部调用和"上下文没建起来"三种情况命中，
     * 而三者的安全含义完全不同。行为不能改（改了就是让定时任务大面积 403），
     * 但静默放行必须先变得可见，否则越权面扩大时没有任何信号。
     * 键区分"带内部服务身份"与"完全无身份"，各告警一次，计数可用
     * {@link #noListGrantHits(String)} 查询。
     */
    private static void recordNoListGrant() {
        String service = getInternalService();
        String key = service != null ? "internal-service:" + service : "no-identity";
        long hits = NO_LIST_GRANT_HITS.merge(key, 1L, Long::sum);
        if (hits == 1L) {
            log.warn("店铺授权校验走「无店铺列表即放行」分支：来源={} 命中次数=1"
                    + "（定时任务属预期；若为公网请求上下文缺失，说明鉴权链路有洞）", key);
        }
    }

    /**
     * 「无店铺列表即放行」分支的累计命中次数；key 见 {@link #recordNoListGrant()}。
     */
    public static long noListGrantHits(String key) {
        Long hits = NO_LIST_GRANT_HITS.get(key);
        return hits == null ? 0L : hits;
    }

    /**
     * 外部请求边界的严格店铺授权判断。
     * <p>
     * 非 ADMIN 用户必须携带非空 shops，且目标 shopId 必须命中授权列表。
     * 缺少 shops claim 的合法 JWT 不能因为“没有限制”被误解释为“可访问所有店铺”。
     */
    public static boolean isShopAllowedStrict(Long shopId) {
        if (shopId == null) {
            return false;
        }
        if ("ADMIN".equalsIgnoreCase(getRole())) {
            return true;
        }
        List<Long> shops = getShops();
        return shops != null && !shops.isEmpty() && shops.contains(shopId);
    }

    /**
     * 用户店铺授权或已认证可信服务二选一。
     * <p>
     * 仅用于同时声明 {@code @InternalServiceAccess} 的双信任端点。服务身份已经过
     * 短时令牌签名和调用方白名单校验，因此可跨用户上下文执行；普通用户仍按严格店铺列表校验。
     * 未声明双信任的端点不得调用本方法，避免把服务身份误扩散为全局店铺通行证。
     */
    public static boolean isShopAllowedByUserOrTrustedService(Long shopId) {
        return shopId != null && (getInternalService() != null || isShopAllowedStrict(shopId));
    }

    public static void clear() {
        userThreadLocal.remove();
        shopThreadLocal.remove();
        roleThreadLocal.remove();
        shopsThreadLocal.remove();
        shopRolesThreadLocal.remove();
        internalServiceThreadLocal.remove();
    }
}
