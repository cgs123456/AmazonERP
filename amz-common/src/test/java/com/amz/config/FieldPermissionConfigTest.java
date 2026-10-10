package com.amz.config;

import com.amz.service.FieldPermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FieldPermissionConfig 预热条件契约：
 * bootstrap profile 是一次性导入器，禁用 Web 类型且导入后退出，
 * 此时 amz_user 规则表可能尚未建表，预热必然失败，应跳过预热。
 */
@DisplayName("FieldPermissionConfig 预热条件")
class FieldPermissionConfigTest {

    private final FieldPermissionService service = mock(FieldPermissionService.class);
    private final FieldPermissionConfig config = new FieldPermissionConfig();

    private void init(MockEnvironment environment) {
        ReflectionTestUtils.setField(config, "fieldPermissionService", service);
        ReflectionTestUtils.setField(config, "environment", environment);
        config.init();
    }

    @Test
    @DisplayName("bootstrap profile 跳过预热")
    void skipWhenBootstrapProfileActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("bootstrap");

        init(env);

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("多 profile 同时含 bootstrap 时跳过预热")
    void skipWhenBootstrapAmongActiveProfiles() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "bootstrap");

        init(env);

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("prod profile 正常预热")
    void loadWhenProdProfileActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        init(env);

        verify(service).loadPermissions();
    }

    @Test
    @DisplayName("未设置 profile 时正常预热")
    void loadWhenNoProfileActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles();

        init(env);

        verify(service).loadPermissions();
    }
}
