package com.amz.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明可信服务身份白名单。
 *
 * <p>标注在控制器方法或控制器类型上。对于 {@code /internal/**}，端点只接受
 * 服务令牌；对于普通业务端点，端点接受用户 JWT 或白名单中的服务令牌。
 * 缺少该注解时，服务令牌不得访问；{@code BaseAuthInterceptor} 必须 fail-closed，
 * 禁止任何已签名服务默认全通。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalServiceAccess {

    /**
     * 允许调用该端点的 {@code spring.application.name} 列表。
     */
    String[] value();
}
