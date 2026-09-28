package com.amz.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ProductionProfileGuard：prod 与 mock 不得同时激活")
class ProductionProfileGuardTest {

    @Test
    @DisplayName("prod,mock 同时激活时必须在启动期拒绝")
    void rejectsProdAndMockTogether() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "mock");
        ProductionProfileGuard guard = new ProductionProfileGuard(environment);

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(ex.getMessage().contains("prod"));
        assertTrue(ex.getMessage().contains("mock"));
    }

    @Test
    @DisplayName("仅 prod 激活时允许真实模式")
    void allowsProdOnly() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertDoesNotThrow(() -> new ProductionProfileGuard(environment).verify());
    }

    @Test
    @DisplayName("仅 mock 激活时允许显式离线演示")
    void allowsMockOnly() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mock");

        assertDoesNotThrow(() -> new ProductionProfileGuard(environment).verify());
    }
}