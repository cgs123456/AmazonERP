package com.amz.context;

/**
 * 用户线程变量（增强：多店铺 shopId）
 */
public class UserContext {
    public static final ThreadLocal<Integer> userThreadLocal = new ThreadLocal<>();
    public static final ThreadLocal<Long> shopThreadLocal = new ThreadLocal<>();

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

    public static void clear() {
        userThreadLocal.remove();
        shopThreadLocal.remove();
    }
}
