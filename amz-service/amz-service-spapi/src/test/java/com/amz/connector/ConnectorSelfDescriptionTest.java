package com.amz.connector;

import com.amz.credential.ConnectorStartupCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-52d 契约：连接器自描述必须让验收 runbook 的硬约束 C1
 * （「被测服务必须以 prod 启动」）在进程外可核验，且<b>不新增任何机密出口</b>。
 * <p>
 * 证据类型 E1（自证：同一实现的字段视图，不含真实进程）。
 */
@DisplayName("P0-52d 连接器自描述：profile / mock 开关 / 凭证条数可核验且无机密")
class ConnectorSelfDescriptionTest {

    /** 冻结的键集合：新增键必须同时更新本用例与 ConnectorSelfDescription 的类注释。 */
    private static final Set<String> EXPECTED_KEYS = Set.of(
            "service", "connector", "profile", "mockClientsActive",
            "startupCheckRan", "startupRequireCredentials", "loadedCredentialCount");

    private static ConnectorStartupCheck.StartupState state(String[] profiles, boolean mock,
                                                            boolean requireCredentials, int credentialCount) {
        return new ConnectorStartupCheck.StartupState(profiles, mock, requireCredentials, credentialCount);
    }

    @Test
    @DisplayName("prod 启动快照：profile=prod、mock=false、凭证条数如实回报")
    void prodStartupStateIsReported() {
        Map<String, Object> out = ConnectorSelfDescription.of(
                null, state(new String[]{"prod"}, false, true, 2));

        assertEquals("amz-service-spapi", out.get("service"));
        assertEquals("spapi", out.get("connector"));
        assertEquals("prod", out.get("profile"));
        assertEquals(Boolean.FALSE, out.get("mockClientsActive"));
        assertEquals(Boolean.TRUE, out.get("startupCheckRan"));
        assertEquals(Boolean.TRUE, out.get("startupRequireCredentials"));
        assertEquals(2, out.get("loadedCredentialCount"));
    }

    @Test
    @DisplayName("mock 混入启动快照：mockClientsActive=true（验收 runner 必须据此拒绝出报告）")
    void mockProfileIsFlaggedFromStartupState() {
        Map<String, Object> out = ConnectorSelfDescription.of(
                null, state(new String[]{"prod", "mock"}, true, true, 1));

        assertEquals("prod,mock", out.get("profile"));
        assertEquals(Boolean.TRUE, out.get("mockClientsActive"));
    }

    @Test
    @DisplayName("未执行启动自检：回落到实时 Environment，并显式标记 startupCheckRan=false、条数=-1")
    void fallsBackToLiveEnvironmentWhenCheckDidNotRun() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mock");
        environment.setProperty(ConnectorStartupCheck.REQUIRE_CREDENTIALS_KEY, "true");

        Map<String, Object> out = ConnectorSelfDescription.of(environment, null);

        assertEquals("mock", out.get("profile"));
        assertEquals(Boolean.TRUE, out.get("mockClientsActive"));
        assertEquals(Boolean.FALSE, out.get("startupCheckRan"));
        assertEquals(Boolean.TRUE, out.get("startupRequireCredentials"));
        assertEquals(ConnectorSelfDescription.UNKNOWN_CREDENTIAL_COUNT, out.get("loadedCredentialCount"));
    }

    @Test
    @DisplayName("启动快照优先于实时 Environment：自检结果不被运行期改动覆盖")
    void startupStateWinsOverLiveEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mock");

        Map<String, Object> out = ConnectorSelfDescription.of(
                environment, state(new String[]{"prod"}, false, true, 3));

        assertEquals("prod", out.get("profile"));
        assertEquals(Boolean.FALSE, out.get("mockClientsActive"));
    }

    @Test
    @DisplayName("空环境与空快照：不抛异常、键集合仍然完整、profile 为空串")
    void toleratesNullEnvironmentAndNullState() {
        Map<String, Object> out = ConnectorSelfDescription.of(null, null);

        assertEquals(EXPECTED_KEYS, out.keySet());
        assertEquals("", out.get("profile"));
        assertEquals(Boolean.FALSE, out.get("mockClientsActive"));
        assertEquals(Boolean.FALSE, out.get("startupRequireCredentials"));
        assertEquals(-1, out.get("loadedCredentialCount"));
    }

    @Test
    @DisplayName("键集合冻结：不得新增凭证类字段（防止机密借自描述端点外泄）")
    void freezesKeySurface() {
        Map<String, Object> out = ConnectorSelfDescription.of(
                null, state(new String[]{"prod"}, false, true, 0));

        assertEquals(EXPECTED_KEYS, out.keySet());
        for (Map.Entry<String, Object> entry : out.entrySet()) {
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            assertFalse(key.contains("secret") || key.contains("token") || key.contains("password")
                            || key.contains("credentialid") || key.contains("accesskey"),
                    "自描述键名不得指向凭证内容：" + entry.getKey());
            assertTrue(entry.getValue() == null || entry.getValue() instanceof String
                            || entry.getValue() instanceof Boolean || entry.getValue() instanceof Integer,
                    "自描述值只允许标量：" + entry.getKey());
        }
    }
}