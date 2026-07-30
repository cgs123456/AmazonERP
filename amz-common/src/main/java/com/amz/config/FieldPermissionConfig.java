package com.amz.config;

import com.amz.service.FieldPermissionService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * 字段级数据权限配置。
 * <p>
 * 容器启动后调用 {@link FieldPermissionService#loadPermissions()} 预热 Redis 缓存。
 * 加载失败不阻断启动（FieldPermissionServiceImpl 内部已降级）。
 * <p>
 * {@code @EnableAspectJAutoProxy} 由 spring-boot-starter-aop 自动开启，无需重复声明。
 */
@Slf4j
@Configuration
public class FieldPermissionConfig {

    @Autowired
    private FieldPermissionService fieldPermissionService;

    @PostConstruct
    public void init() {
        try {
            fieldPermissionService.loadPermissions();
        } catch (Exception e) {
            log.warn("FieldPermissionConfig 预热失败，降级为「全部可见」: {}", e.getMessage());
        }
    }
}
