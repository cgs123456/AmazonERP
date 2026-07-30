package com.amz.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志审计注解。
 * <p>
 * 标注于 Controller / Service 方法上，由 {@code OperLogAspect} 拦截并异步记录
 * 操作人、模块、动作、参数、返回值、异常、请求 IP 等审计信息到独立日志文件。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {

    /**
     * 模块名，如「订单」「商品」「店铺」。
     */
    String module();

    /**
     * 操作类型，如「查询」「新增」「修改」「删除」「导出」。
     */
    String action();

    /**
     * 描述，可选。
     */
    String description() default "";
}
