package com.amz.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Agent 工具权限标注（A-3）。
 * <p>
 * 标注在 {@code ErpToolExecutor} 的工具处理方法上，声明调用该工具所需的最低权限：
 * <ul>
 *   <li>{@code agent:readonly}（缺省）：只读/分析类工具，所有登录用户可用；</li>
 *   <li>{@code agent:operate}：会产生副作用的工具（下单/回复/调价/跨店搬运），仅 OPERATOR 及以上可用。</li>
 * </ul>
 * 强制执行点见 {@code ErpToolExecutor#OPERATE_TOOLS} 与权限校验；
 * {@code ToolPermissionTest} 用反射双向核对标注与集合一致，防漂移。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolPermission {

    String value() default "agent:readonly";
}
