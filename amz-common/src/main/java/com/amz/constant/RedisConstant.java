package com.amz.constant;

/**
 * Redis Key 常量。
 * <p>
 * 已清理社交场景的 NOTE/LIKE/COLLECTION 及 B2C Shopping Agent / 商品缓存 / 布隆过滤器相关 key，
 * 仅保留 ERP 业务所需 key。
 */
public class RedisConstant {
    public static final String PHONE_CODE = "amz:user:phone_code:";
    public static final String PRODUCT_SCORE = "amz:product:product_score:";

    // ===== 字段级数据权限 =====
    /** 字段权限规则缓存前缀，完整 key: amz:field:perm:{role}:{entity} → Set<fieldName>（隐藏字段集合） */
    public static final String FIELD_PERM_PREFIX = "amz:field:perm:";
}