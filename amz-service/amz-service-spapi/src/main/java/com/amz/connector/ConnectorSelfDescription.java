package com.amz.connector;

import com.amz.credential.ConnectorStartupCheck;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SP-API 连接器自描述（P0-52d）：把「进程实际以什么 profile 启动 + mock 样例客户端是否激活
 * + 启动自检要求的凭证是否已到达进程」变成<b>运行时可观测</b>的只读视图。
 * <p>
 * <b>为什么需要：</b>{@code SpapiController#status} 原先只返回固定串
 * {@code "SP-API service running"}，而凭证到位当天的一次性验收 runbook 有硬约束 C1
 * （「必须以 {@code SPRING_PROFILES_ACTIVE=prod} 启动，违反则整份验收记录作废」）。
 * 进程外没有任何渠道能核验这条约束——mock profile 下 {@code ReportsMockClient} /
 * {@code FinancesMockClient} / {@code FeesMockClient} 会返回<b>离线样例数据</b>，
 * 据此产出的「成功样例」是假证据，「一条命令出报告」因此只能靠人工声明。
 * 本类把该声明变成机器可核验字段。
 * <p>
 * <b>数据来源优先级：</b>{@link ConnectorStartupCheck#getLastState()}（启动时的真实快照，
 * 含当时激活的 profile 与已加载凭证条数）→ 未执行自检时回落到 {@link Environment} 的实时值，
 * 并把 {@code startupCheckRan=false} 如实写出，不假装自检跑过。
 * <p>
 * <b>不含任何机密：</b>只输出 profile 名、布尔开关与凭证<b>条数</b>（不含 clientId /
 * clientSecret / refreshToken / accessKey / secretKey / 任何 token）。
 * {@code ConnectorSelfDescriptionTest} 以「键集合冻结」用例锁死这一点。
 */
public final class ConnectorSelfDescription {

    /** 服务名（与被测进程一致）。 */
    public static final String SERVICE = "amz-service-spapi";

    /** 连接器标识（与 runbook §3.1 的 {@code -Connector} 参数一致）。 */
    public static final String CONNECTOR = "spapi";

    /** 未执行启动自检时凭证条数的占位值（未知，不是 0）。 */
    public static final int UNKNOWN_CREDENTIAL_COUNT = -1;

    private ConnectorSelfDescription() {
    }

    /**
     * 构造自描述视图（只读，永不返回 null 值）。
     *
     * @param environment  Spring 环境，可为 null
     * @param startupState 启动自检快照（{@link ConnectorStartupCheck#getLastState()}），可为 null
     * @return 固定键集合的有序 Map：{@code service} / {@code connector} / {@code profile} /
     *         {@code mockClientsActive} / {@code startupCheckRan} / {@code startupRequireCredentials} /
     *         {@code loadedCredentialCount}
     */
    public static Map<String, Object> of(Environment environment,
                                         ConnectorStartupCheck.StartupState startupState) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", SERVICE);
        out.put("connector", CONNECTOR);
        out.put("profile", profile(environment, startupState));
        out.put("mockClientsActive", startupState != null
                ? startupState.mockActive()
                : mockProfileActive(activeProfiles(environment)));
        out.put("startupCheckRan", startupState != null);
        out.put("startupRequireCredentials", startupState != null
                ? startupState.credentialsRequired()
                : requireCredentials(environment));
        out.put("loadedCredentialCount", startupState != null
                ? startupState.credentialCount()
                : UNKNOWN_CREDENTIAL_COUNT);
        return out;
    }

    /** 激活 profile 的逗号拼接（无激活 profile 时为空串，绝不返回 null）。 */
    public static String profile(Environment environment, ConnectorStartupCheck.StartupState startupState) {
        String[] profiles = startupState != null
                ? startupState.activeProfiles()
                : activeProfiles(environment);
        if (profiles == null || profiles.length == 0) {
            return "";
        }
        return String.join(",", profiles);
    }

    private static String[] activeProfiles(Environment environment) {
        return environment == null ? new String[0] : environment.getActiveProfiles();
    }

    private static boolean mockProfileActive(String[] activeProfiles) {
        return activeProfiles != null && Arrays.stream(activeProfiles)
                .anyMatch(profile -> ConnectorStartupCheck.MOCK_PROFILE.equalsIgnoreCase(profile));
    }

    private static boolean requireCredentials(Environment environment) {
        return environment != null && Boolean.TRUE.equals(environment.getProperty(
                ConnectorStartupCheck.REQUIRE_CREDENTIALS_KEY, Boolean.class, Boolean.FALSE));
    }
}
