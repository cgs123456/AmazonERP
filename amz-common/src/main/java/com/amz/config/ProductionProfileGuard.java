package com.amz.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * 生产 profile 与离线 mock profile 的启动期互斥守卫。
 *
 * <p>系统允许在没有任何平台凭证时以单独的 {@code mock} profile 启动离线演示，
 * 也允许在凭证就绪后以单独的 {@code prod} profile 启动真实连接器。两种模式不得
 * 同时激活，否则不同连接器可能分别装配真实客户端与 mock 客户端，导致部分业务
 * 静默返回样例数据，而监控上仍显示为生产实例。</p>
 *
 * <p>该校验位于公共模块，所有业务服务均可扫描到；即使某个连接器没有把
 * “凭证缺失”当作启动失败，也无法绕过混合 profile 检查。</p>
 */
@Component
public class ProductionProfileGuard {

    /** 生产 profile。 */
    public static final String PROD_PROFILE = "prod";

    /** 离线样例数据 profile。 */
    public static final String MOCK_PROFILE = "mock";

    private final Environment environment;

    public ProductionProfileGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * 在 Spring Bean 初始化阶段拒绝 {@code prod,mock} 混合激活。
     *
     * @throws IllegalStateException 当两个 profile 同时激活时
     */
    @PostConstruct
    public void verify() {
        String[] activeProfiles = environment.getActiveProfiles();
        boolean prodActive = containsIgnoreCase(activeProfiles, PROD_PROFILE);
        boolean mockActive = containsIgnoreCase(activeProfiles, MOCK_PROFILE);

        if (prodActive && mockActive) {
            throw new IllegalStateException(String.format(
                    "启动配置冲突：profile 不得同时包含 %s 与 %s（activeProfiles=%s）。"
                            + "prod 表示连接真实平台，mock 表示使用离线样例数据；"
                            + "混用会让部分连接器静默返回模拟结果。"
                            + "请只设置 SPRING_PROFILES_ACTIVE=prod 以连接真实 API，"
                            + "或只设置 SPRING_PROFILES_ACTIVE=mock 进行离线演示。",
                    PROD_PROFILE, MOCK_PROFILE, Arrays.toString(activeProfiles)));
        }
    }

    private static boolean containsIgnoreCase(String[] profiles, String expected) {
        if (profiles == null) {
            return false;
        }
        String normalizedExpected = expected.toLowerCase(Locale.ROOT);
        return Arrays.stream(profiles)
                .filter(profile -> profile != null)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedExpected::equals);
    }
}
