package com.amz.annotation;

import com.amz.enums.ConfidentialLevel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级数据权限注解。
 * <p>
 * 标注在实体类的字段上，声明其敏感等级。由 {@code FieldPermissionAspect} 在
 * Controller 返回值序列化前根据当前用户角色与 {@code amz_field_permission} 规则
 * 判断是否需要将字段值置 null。
 * <p>
 * 仅标注了本注解的字段才会被过滤；未标注的字段保持原值。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldPermission {

    /**
     * 字段敏感级别，默认 INTERNAL。
     */
    ConfidentialLevel sensitiveLevel() default ConfidentialLevel.INTERNAL;
}
