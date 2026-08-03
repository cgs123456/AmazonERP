package com.amz.context;

import java.util.List;

/**
 * 用户线程变量（增强：多店铺 shopId + 角色 role + 授权店铺列表 shops）。
 */
public class UserContext {
    public static final ThreadLocal<Integer> userThreadLocal = new ThreadLocal<>();
    public static final ThreadLocal<Long> shopThreadLocal = new ThreadLocal<>();
    /** 当前用户角色代码：ADMIN / OPERATOR / VIEWER，由 BaseAuthInterceptor 从 JWT 解析注入。 */
    public static final ThreadLocal<String> roleThreadLocal = new ThreadLocal<>();
    /** 当前用户授权访问的店铺 id 列表，由 BaseAuthInterceptor 从 JWT shops claim 解析注入。供下游 shopId 二次校验使用。 */
    public static final ThreadLocal<List<Long>> shopsThreadLocal = new ThreadLocal<>();

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
     * 判断指定 shopId 是否在当前用户授权店铺列表内（供 {@code @RequestBody} 或非注解参数场景的二次校验）。
     * <p>
     * 信任模型与 {@link com.amz.aspect.ShopIdGuardAspect} 保持一致：
     * 当 {@link #getShops()} 为 null 或空（内部调用 / 白名单 / 未携带 shops claim）时跳过校验并返回
     * {@code true}，避免阻断内部或管理流；否则要求 shopId 非空且命中授权列表。
     * <p>
     * 用于 {@code @ShopScoped} 切面无法覆盖的 {@code @RequestBody} 内嵌 shopId（如凭证写入、Listing 复制），
     * 防止请求体伪造店铺 id 越权访问其他租户数据。
     *
     * @param shopId 待校验店铺 id（可为 null）
     * @return true 表示允许访问；false 表示越权
     */
    public static boolean isShopAllowed(Long shopId) {
        List<Long> shops = getShops();
        if (shops == null || shops.isEmpty()) {
            return true;
        }
        return shopId != null && shops.contains(shopId);
    }

    public static void clear() {
        userThreadLocal.remove();
        shopThreadLocal.remove();
        roleThreadLocal.remove();
        shopsThreadLocal.remove();
    }
}
