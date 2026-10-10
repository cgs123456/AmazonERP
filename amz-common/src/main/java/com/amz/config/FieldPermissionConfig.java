package com.amz.config;

import com.amz.service.FieldPermissionService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 字段级数据权限配置。
 * <p>
 * 容器启动后调用 {@link FieldPermissionService#loadPermissions()} 预热 Redis 缓存。
 * 加载失败不阻断启动（FieldPermissionServiceImpl 内部已降级）。
 * <p>
 * {@code bootstrap} profile 是一次性数据导入器：禁用 Web 类型、导入后退出、无请求面，
 * 且导入过程中 {@code amz_user} 规则表可能尚未建表，预热必然失败，因此跳过预热。
 * <p>
 * {@code @EnableAspectJAutoProxy} 由 spring-boot-starter-aop 自动开启，无需重复声明。
 */
@Slf4j
@Configuration
public class FieldPermissionConfig {

    /** 一次性导入器专用 profile，见 application-bootstrap.yml。 */
    private static final String BOOTSTRAP_PROFILE = "bootstrap";

    @Autowired
    private FieldPermissionService fieldPermissionService;

    @Autowired
    private Environment environment;

    @PostConstruct
    public void init() {
        if (environment.acceptsProfiles(Profiles.of(BOOTSTRAP_PROFILE))) {
            log.info("bootstrap profile 跳过字段权限预热（无请求面，规则表由导入流程建立）");
            return;
        }
        try {
            fieldPermissionService.loadPermissions();
        } catch (Exception e) {
            log.warn("FieldPermissionConfig 预热失败，降级为「全部可见」: {}", e.getMessage());
        }
    }
}
