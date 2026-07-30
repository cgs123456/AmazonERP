package com.amz.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 操作日志审计配置：启用 {@code @Async} 以支持 {@code OperLogAspect} 异步落盘，
 * 避免日志写入阻塞业务线程。
 * <p>
 * {@code @EnableAspectJAutoProxy} 由 spring-boot-starter-aop 的 AopAutoConfiguration 自动开启，
 * 此处无需重复声明。
 */
@Configuration
@EnableAsync
public class OperLogConfig {
}
