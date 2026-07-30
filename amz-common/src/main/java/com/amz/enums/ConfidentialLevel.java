package com.amz.enums;

/**
 * 字段敏感级别枚举，配合 {@code com.amz.annotation.FieldPermission} 使用。
 * <p>
 * 字段权限规则表 {@code amz_field_permission} 中的 {@code visible} 字段决定
 * 某角色对该字段的最终可见性；本枚举仅用于声明字段本身的敏感等级，
 * 便于审计、UI 提示以及未来基于等级的批量授权策略。
 */
public enum ConfidentialLevel {

    /** 公开：所有角色可见，无需标注，仅作显式声明用。 */
    PUBLIC,

    /** 内部：仅 OPERATOR/ADMIN 可见，VIEWER 默认隐藏。 */
    INTERNAL,

    /** 机密：仅 ADMIN（或显式授权的 OPERATOR）可见。 */
    CONFIDENTIAL
}
