package com.amz.credential;

import com.amz.util.CryptoUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产启动自检（fail-closed）单元测试。
 * <p>
 * 用户口径：暂时没有平台 API 凭证，但必须“有凭证即可直接用”。
 * 这条口径的工程前提是：生产环境**不能**在缺凭证或 mock profile 下静默启动——
 * 否则服务会以“看起来正常”的状态对外提供服务，把缺凭证伪装成空数据。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>require-credentials=true + mock profile → 拒绝启动，消息含 mock</li>
 *   <li>require-credentials=true + 凭证表为空 → 拒绝启动，消息含 amz_shop_credential</li>
 *   <li>require-credentials=false → 不抛异常（开发/CI 离线口径不变）</li>
 *   <li>require-credentials=true + 非 mock + 至少一条凭证 → 正常通过</li>
 * </ul>
 */
@DisplayName("ConnectorStartupCheck 生产启动自检")
class ConnectorStartupCheckTest {

    private static final String REQUIRE_KEY = "spapi.startup.require-credentials";

    /** 32 字节 base64 密钥，仅用于测试加密路径，不得用于任何真实环境。 */
    private static final String TEST_CRYPTO_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private static MockEnvironment env(String profiles, String requireCredentials) {
        MockEnvironment environment = new MockEnvironment();
        if (profiles != null && !profiles.isEmpty()) {
            environment.setActiveProfiles(profiles.split(","));
        }
        if (requireCredentials != null) {
            environment.setProperty(REQUIRE_KEY, requireCredentials);
        }
        return environment;
    }

    private static ShopCredentialStore emptyStore() {
        return new ShopCredentialStore();
    }

    /** 构造一个已缓存 1 条凭证的 store（mapper 未注入 → 不落库）。 */
    private static ShopCredentialStore storeWithOneCredential() {
        CryptoUtil cryptoUtil = new CryptoUtil();
        ReflectionTestUtils.setField(cryptoUtil, "cryptoKey", TEST_CRYPTO_KEY);
        cryptoUtil.init();

        ShopCredentialStore store = new ShopCredentialStore();
        ReflectionTestUtils.setField(store, "cryptoUtil", cryptoUtil);

        ShopCredential credential = new ShopCredential();
        credential.setShopId(1L);
        credential.setClientId("amzn1.application-oa2-client.test");
        credential.setClientSecret("test-client-secret");
        credential.setRefreshToken("Atzr|test-refresh-token");
        credential.setRegion("NA");
        credential.setMarketplaceId("ATVPDKIKX0DER");
        credential.setSellerId("A1TESTSHOP");
        store.put(credential);
        return store;
    }

    @Test
    @DisplayName("require-credentials=true 且启用 mock profile → 拒绝启动，提示 mock")
    void rejectsMockProfileWhenCredentialsRequired() {
        ConnectorStartupCheck check = new ConnectorStartupCheck(env("mock", "true"), emptyStore());

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::verify);

        assertTrue(ex.getMessage().contains("mock"),
                "异常消息必须点明 mock profile，实际为：" + ex.getMessage());
    }

    @Test
    @DisplayName("require-credentials=true 且无任何店铺凭证 → 拒绝启动，提示 amz_shop_credential")
    void rejectsWhenCredentialTableEmpty() {
        ShopCredentialStore store = emptyStore();
        ConnectorStartupCheck check = new ConnectorStartupCheck(env("prod", "true"), store);

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::verify);

        assertTrue(ex.getMessage().contains("amz_shop_credential"),
                "异常消息必须点明凭证表 amz_shop_credential，实际为：" + ex.getMessage());
    }

    @Test
    @DisplayName("require-credentials=false（默认）→ 离线/CI 口径不变，不抛异常")
    void skipsCheckWhenNotRequired() {
        ConnectorStartupCheck check = new ConnectorStartupCheck(env("mock", "false"), emptyStore());

        assertDoesNotThrow(check::verify);
    }

    @Test
    @DisplayName("require-credentials=true 且非 mock 且已有凭证 → 通过")
    void passesWhenCredentialsPresent() {
        ConnectorStartupCheck check = new ConnectorStartupCheck(env("prod", "true"), storeWithOneCredential());

        assertDoesNotThrow(check::verify);
    }
}