package com.amz.service;

import java.util.Set;

/**
 * 字段级数据权限服务。
 * <p>
 * 启动时从 amz_field_permission 表加载规则到 Redis 缓存，
 * 运行时由 AOP 切面查询当前角色对某实体的隐藏字段集合，对返回对象做反射置 null。
 */
public interface FieldPermissionService {

    /**
     * 启动时从 DB 加载全部权限规则到 Redis。失败时降级为「全部可见」，不阻断启动。
     */
    void loadPermissions();

    /**
     * 获取指定角色 + 实体下需隐藏的字段集合。
     * 缓存未命中或服务不可用时返回空集合（即全部可见，不阻断业务）。
     *
     * @param role       角色代码 ADMIN/OPERATOR/VIEWER
     * @param entityName 实体类简单名，如 PurchaseOrder
     * @return 需置空的字段名集合，可能为空
     */
    Set<String> getHiddenFields(String role, String entityName);

    /**
     * 判断字段是否对当前角色可见。
     *
     * @param role       角色代码
     * @param entityName 实体类简单名
     * @param fieldName  Java 字段名
     * @return true 表示可见（不在隐藏集合中）；false 表示需置空
     */
    boolean isFieldVisible(String role, String entityName, String fieldName);
}
