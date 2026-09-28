package com.amz.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DataSourceValidator：生产环境关键中间件密码必须 fail-closed")
class DataSourceValidatorTest {

    @Test
    @DisplayName("prod 下数据库密码为空时拒绝启动")
    void rejectsBlankDatabasePasswordInProd() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", "");
        environment.setActiveProfiles("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DataSourceValidator(environment).validate());

        assertTrue(ex.getMessage().contains("DB"));
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
    }

    @ParameterizedTest(name = "prod 下占位密码 [{0}] 必须拒绝启动")
    @ValueSource(strings = {"your_password", "CHANGE_ME_MYSQL_PASSWORD", "changeme", "placeholder"})
    void rejectsPlaceholderDatabasePasswordInProd(String password) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", password);
        environment.setActiveProfiles("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DataSourceValidator(environment).validate());

        assertTrue(ex.getMessage().contains("spring.datasource.password"));
        assertTrue(ex.getMessage().contains("占位"));
    }

    @Test
    @DisplayName("非 prod 下空密码允许离线开发启动")
    void allowsBlankPasswordOutsideProd() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", "");
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> new DataSourceValidator(environment).validate());
    }

    @Test
    @DisplayName("未配置的中间件密码不参与校验")
    void skipsUnconfiguredPasswordProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertDoesNotThrow(() -> new DataSourceValidator(environment).validate());
    }
}
