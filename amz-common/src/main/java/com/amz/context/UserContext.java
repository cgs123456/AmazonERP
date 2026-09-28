package com.amz.context;

import java.util.List;

/**
 * 用户线程变量（多店铺 shopId + 角色 role + 授权店铺列表 shops + 可信服务身份）。
 */
public class UserContext {
    public static final ThreadLocal<Integer> userThreadLocal = new ThreadLocal<>();
    public static final ThreadLocal<Long> shopThreadLocal = new ThreadLocal<>();
    /** 当前用户角色代码：ADMIN / OPERATOR / VIEWER，由 BaseAuthInterceptor 从 JWT 解析注入。 */
    public static final ThreadLocal<String> roleThreadLocal = new ThreadLocal<>();
    /** 当前用户授权访问的店铺 id 列表，由 BaseAuthInterceptor 从 JWT shops claim 解析注入。 */
    public static final ThreadLocal<List<Long>> shopsThreadLocal = new ThreadLocal<>();
    /** 当前可信服务身份，仅在服务令牌校验通过并命中 @InternalServiceAccess 白名单后写入。 */
    public static final ThreadLocal<String> internalServiceThreadLocal = new ThreadLocal<>();

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
            return true;
        }
        return shopId != null && shops.contains(shopId);
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
        internalServiceThreadLocal.remove();
    }
}
