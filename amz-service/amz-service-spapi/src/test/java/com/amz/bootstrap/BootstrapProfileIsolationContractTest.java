package com.amz.bootstrap;

import com.amz.config.SchedulingConfig;
import com.amz.scheduler.InventorySyncScheduler;
import com.amz.scheduler.OrderSyncScheduler;
import com.amz.scheduler.ReplenishmentScheduler;
import com.amz.scheduler.SpApiOutboxReplayScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("bootstrap profile 隔离与事务边界契约")
class BootstrapProfileIsolationContractTest {

    @Test
    @DisplayName("bootstrap 不加载调度配置或任何业务调度器")
    void bootstrapDoesNotLoadSchedulers() {
        List<Class<?>> guardedTypes = List.of(
                SchedulingConfig.class,
                OrderSyncScheduler.class,
                InventorySyncScheduler.class,
                ReplenishmentScheduler.class,
                SpApiOutboxReplayScheduler.class);

        for (Class<?> type : guardedTypes) {
            Profile profile = type.getAnnotation(Profile.class);
            assertNotNull(profile, type.getSimpleName() + " 必须声明 @Profile");
            assertTrue(Arrays.asList(profile.value()).contains("!bootstrap"),
                    type.getSimpleName() + " 必须在 bootstrap profile 下禁用");
        }
        assertNotNull(SchedulingConfig.class.getAnnotation(EnableScheduling.class),
                "@EnableScheduling 必须随 SchedulingConfig 一起被 bootstrap 排除");
    }

    @Test
    @DisplayName("批量导入服务只在 bootstrap 生效且写入方法带事务")
    void importerIsBootstrapOnlyAndTransactional() throws Exception {
        Class<SpapiCredentialBootstrapImporter> type = SpapiCredentialBootstrapImporter.class;
        assertNotNull(type.getAnnotation(Service.class));
        Profile profile = type.getAnnotation(Profile.class);
        assertNotNull(profile);
        assertTrue(Arrays.asList(profile.value()).contains("bootstrap"));

        Method method = type.getMethod("importCredentials", java.nio.file.Path.class);
        assertNotNull(method.getAnnotation(Transactional.class));
    }
}