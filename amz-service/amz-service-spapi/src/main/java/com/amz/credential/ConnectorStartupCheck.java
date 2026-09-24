package com.amz.credential;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 连接器生产启动自检（fail-closed）。
 * <p>
 * 用户口径：暂时没有平台 API 凭证，但系统必须“有凭证即可直接用”。这条口径要成立，
 * 前提是**失效必须可见**：生产环境绝不能在缺凭证或 mock（离线样例数据）profile 下静默启动，
 * 否则服务会以“看起来正常”的状态对外提供服务，把“没有凭证”伪装成“今天没有订单”。
 * <p>
 * 由 {@code spapi.startup.require-credentials} 控制（生产 profile 下由
 * {@code application-prod.yml} 置为 true；默认 false，保持离线开发与 CI 口径不变）：
 * <ul>
 *   <li>true + 启用 mock profile → 抛 {@link IllegalStateException} 拒绝启动</li>
 *   <li>true + {@code amz_shop_credential} 无任何店铺凭证 → 抛 {@link IllegalStateException} 拒绝启动</li>
 *   <li>false → 仅记录 warn 并跳过（离线开发/CI）</li>
 * </ul>
 * <p>
 * 注意：本类只读取凭证**条数**（{@link ShopCredentialStore#getActiveShopIds()}），
 * 不接触任何凭证内容；格式校验（如 AMZ_CRYPTO_KEY 必须 32 字节 base64）由
 * {@code CryptoUtil} 在自身 {@code @PostConstruct} 中 fail-closed 完成。
 * <p>
 * 依赖顺序：本类通过构造器依赖 {@link ShopCredentialStore}，Spring 会先完成 store 的
 * {@code loadFromDb()} 再执行本类的 {@code verify()}，因此此处读到的是启动时的最终缓存状态。
 */
@Slf4j
@Component
public class ConnectorStartupCheck {

    /** 开关键；生产 profile 必须为 true。 */
    public static final String REQUIRE_CREDENTIALS_KEY = "spapi.startup.require-credentials";

    /** 离线样例数据 profile；生产启用即视为配置错误。 */
    public static final String MOCK_PROFILE = "mock";

    /** 店铺凭证表名（仅在错误消息与日志中使用）。 */
    public static final String CREDENTIAL_TABLE = "amz_shop_credential";

    private final Environment environment;
    private final ShopCredentialStore credentialStore;

    /** 最近一次自检结果，供能力清单/自检端点（Task 6）读取；不含任何凭证内容。 */
    private volatile StartupState lastState;

    public ConnectorStartupCheck(Environment environment, ShopCredentialStore credentialStore) {
        this.environment = environment;
        this.credentialStore = credentialStore;
    }

    /**
     * 启动自检入口（Spring 生命周期回调）。
     *
     * @throws IllegalStateException 生产要求凭证但处于 mock profile，或没有任何店铺凭证
     */
    @PostConstruct
    public void verify() {
        boolean credentialsRequired = Boolean.TRUE.equals(
                environment.getProperty(REQUIRE_CREDENTIALS_KEY, Boolean.class, Boolean.FALSE));
        String[] activeProfiles = environment.getActiveProfiles();
        boolean mockActive = Arrays.stream(activeProfiles)
                .anyMatch(profile -> MOCK_PROFILE.equalsIgnoreCase(profile));
        int credentialCount = credentialStore.getActiveShopIds().size();

        this.lastState = new StartupState(activeProfiles, mockActive, credentialsRequired, credentialCount);

        if (!credentialsRequired) {
            log.warn("[ConnectorStartupCheck] 未启用凭证强制校验（{} = false）：跳过 mock 与凭证检查。"
                            + "activeProfiles={}, mockActive={}, 已加载店铺凭证={}。"
                            + "生产环境必须在 application-prod.yml 中置为 true。",
                    REQUIRE_CREDENTIALS_KEY, profileSummary(activeProfiles), mockActive, credentialCount);
            return;
        }

        if (mockActive) {
            throw new IllegalStateException(String.format(
                    "生产启动自检失败：%s=true，但 spring.profiles.active 含 mock（activeProfiles=%s）。"
                            + "mock profile 使用离线样例数据，会让“缺凭证”表现为“正常返回”，因此禁止在生产启用。"
                            + "请设置 SPRING_PROFILES_ACTIVE=prod（或显式排除 mock）并配置真实凭证；"
                            + "若确为离线开发，请把 %s 置为 false。",
                    REQUIRE_CREDENTIALS_KEY, profileSummary(activeProfiles), REQUIRE_CREDENTIALS_KEY));
        }

        if (credentialCount == 0) {
            throw new IllegalStateException(String.format(
                    "生产启动自检失败：%s=true，但未从表 %s 加载到任何店铺凭证（凭证表为空，或 DB 不可用导致降级为空缓存）。"
                            + "activeProfiles=%s。请先写入店铺 SP-API 凭证"
                            + "（clientId/clientSecret/refreshToken/region/marketplaceId），写入路径见 spec §4.8；"
                            + "在凭证就绪前，服务不应以生产 profile 启动。",
                    REQUIRE_CREDENTIALS_KEY, CREDENTIAL_TABLE, profileSummary(activeProfiles)));
        }

        log.info("[ConnectorStartupCheck] 启动自检通过：activeProfiles={}, mockActive=false, 已加载店铺凭证={} 条。"
                        + "各连接器的启用状态由能力清单端点（Task 6）按此结果输出。",
                profileSummary(activeProfiles), credentialCount);
    }

    /**
     * 最近一次自检结果（只读快照，不含凭证内容）。自检前调用返回 null。
     */
    public StartupState getLastState() {
        return lastState;
    }

    private static String profileSummary(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return "[]";
        }
        return Arrays.stream(activeProfiles).collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * 自检结果快照。
     *
     * @param activeProfiles     启动时的激活 profile
     * @param mockActive         是否启用 mock profile
     * @param credentialsRequired 是否要求凭证（{@value #REQUIRE_CREDENTIALS_KEY}）
     * @param credentialCount    已加载店铺凭证条数（只计数，不含内容）
     */
    public record StartupState(String[] activeProfiles, boolean mockActive,
                               boolean credentialsRequired, int credentialCount) {
    }
}