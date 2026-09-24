package com.amz.testsupport;

import com.amz.credential.ShopCredential;

/**
 * 测试用**假**凭证工厂（P0-35/P0-38/P0-50 契约测试的输入）。
 * <p>
 * 所有值都是明显的占位符或 AWS 官方文档公开示例值，禁止用于真实环境；
 * {@code AKIAIOSFODNN7EXAMPLE} / {@code wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY}
 * 取自 AWS 官方文档示例（公开、已失效）。
 */
public final class TestCredentials {

    public static final String FAKE_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    public static final String FAKE_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    public static final String FAKE_CLIENT_ID = "amzn1.application-oa2-client.test";
    public static final String FAKE_CLIENT_SECRET = "test-client-secret";
    public static final String FAKE_SELLER_ID = "A1TESTSELLERID";

    private TestCredentials() {
    }

    /** 北美：US（ATVPDKIKX0DER）。 */
    public static ShopCredential northAmerica() {
        return of(1001L, "ATVPDKIKX0DER", "NA");
    }

    /** 欧洲：DE（A1PA6795UKMFR9）。 */
    public static ShopCredential europe() {
        return of(1002L, "A1PA6795UKMFR9", "EU");
    }

    /** 远东：JP（A1VC38T7YXB528）。 */
    public static ShopCredential farEast() {
        return of(1003L, "A1VC38T7YXB528", "FE");
    }

    /** 构造不带动 AWS 密钥的凭证（刷新令牌按 shopId 区分，保证缓存键互不串号）。 */
    public static ShopCredential of(long shopId, String marketplaceId, String region) {
        ShopCredential c = new ShopCredential();
        c.setShopId(shopId);
        c.setClientId(FAKE_CLIENT_ID);
        c.setClientSecret(FAKE_CLIENT_SECRET);
        c.setRefreshToken("Atzr|test-refresh-token-" + shopId);
        c.setMarketplaceId(marketplaceId);
        c.setRegion(region);
        c.setSellerId(FAKE_SELLER_ID);
        return c;
    }

    /** 附加 AWS AK/SK（触发 SigV4 签名分支）。 */
    public static ShopCredential withAwsKeys(ShopCredential credential) {
        credential.setAccessKey(FAKE_ACCESS_KEY);
        credential.setSecretKey(FAKE_SECRET_KEY);
        return credential;
    }
}
