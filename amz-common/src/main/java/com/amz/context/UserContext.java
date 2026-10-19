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
     * 三分支口径（§S33.121 收紧后）：
     * <ol>
     *   <li><b>有用户身份</b> → 委托 {@link #isShopAllowedStrict(Long)}。</li>
     *   <li><b>无用户身份但带内部服务身份</b> → 放行。白名单内部调用
     *       （{@code @InternalServiceAccess}）只设 {@code internalService}、不设 userId，
     *       这一支是它们唯一的通路，收紧它会一并掐断 service 间调用。</li>
     *   <li><b>谁都不是</b> → <b>拒绝</b>。</li>
     * </ol>
     * 第 3 支从"放行"改为"拒绝"的依据（§S33.121 实测，非推断）：全仓定时任务逐个查过，
     * 全部显式 {@code setUserId(SYSTEM_USER_ID)} + {@code setShops(List.of(shopId))}
     * （见 {@code AmazonReturnSyncScheduler} / {@code SettlementSyncScheduler} /
     * {@code ShopPerformanceSyncScheduler} / {@code ListingCopyService}），
     * 走的都是第 1 支；没有一个是靠"没有身份"放行的。
     * <p>
     * 连 §S33.114 §3.2 里「唯一真依赖宽松档」的那条也不成立：
     * {@code LogisticsTrackingSyncScheduler.markDelayed()} 调的
     * {@code markDelayedShipments} 里零处 {@code isShopAllowed}
     * （跨全店扫描、刻意不校验租户），压根不经本方法。
     * 即 43 处 lenient 调用点中，没有一处的正确性依赖被改掉的那一支。
     * <p>
     * 收益不是修一个可达漏洞——公网端点必经 {@code BaseAuthInterceptor}，userId 不会为 null。
     * 收益是让"鉴权链路有洞"从静默放行变成 fail-closed：将来任何新增的无上下文调用方
     * 会立刻失败，而不是悄悄获得全店访问权。新代码请直接用
     * {@link #isShopAllowedStrict(Long)} 或
     * {@link #isShopAllowedByUserOrTrustedService(Long)}，语义更明确。
     */
    public static boolean isShopAllowed(Long shopId) {
        if (getUserId() != null) {
            return isShopAllowedStrict(shopId);
        }
        if (getInternalService() != null) {
            // 内部服务身份：与 isShopAllowedByUserOrTrustedService 同口径，
            // 仍然留痕（该分支的可观测性由 recordNoListGrant 负责）。
            recordNoListGrant();
            return true;
        }
        return false;
    }

    /**
     * 「无店铺列表即放行」这一分支的计数与首次告警。
     * <p>
     * 存在动因：该分支此前同时被定时任务、白名单内部调用和"上下文没建起来"三种情况命中，
     * 而三者的安全含义完全不同。§S33.121 把"上下文没建起来"改成拒绝后，现在只剩
     * 「带内部服务身份」一种来源——告警文案与键仍保留原样，因为
     * {@code no-identity} 这个键的历史计数是判断「收紧后还有没有无身份调用方」
     * 的唯一依据：收紧后它应当<b>停止增长</b>。
     * 计数可用 {@link #noListGrantHits(String)} 查询。
     */
    private static void recordNoListGrant() {
        String service = getInternalService();
        String key = service != null ? "internal-service:" + service : "no-identity";
        long hits = NO_LIST_GRANT_HITS.merge(key, 1L, Long::sum);
        if (hits == 1L) {
            log.warn("店铺授权校验走「无店铺列表即放行」分支：来源={} 命中次数=1"
                    + "（内部服务身份属预期；无身份来源在 §S33.121 后已不再放行，"
                    + "若该键仍在增长说明存在绕过改造的调用方）", key);
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
