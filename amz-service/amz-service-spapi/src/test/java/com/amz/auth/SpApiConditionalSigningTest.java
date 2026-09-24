package com.amz.auth;

import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.UnknownMarketplaceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-38（有条件 SigV4）+ P0-48（AWS region 映射）。
 * <p>
 * 依据（一手，2026-09-24 抓取）：官方 {@code connecting-to-the-selling-partner-api} 页
 * “The following example shows how to call the SP-API with a URI and headers but
 * <b>no signing information</b>”，该示例只带
 * {@code host} / {@code user-agent} / {@code x-amz-access-token} / {@code x-amz-date}，
 * <b>没有</b> {@code Authorization}；Amazon 自 2023-10-02 起忽略 SigV4 签名。
 * 本仓库保留签名器作为前向保险，但**缺 AK/SK 时必须能无签名调用**，
 * 且不得产出 {@code Credential=null} 之类的垃圾头。
 * <p>
 * P0-48：{@code MarketplaceRegistry} 的 region 是 NA/EU/FE **分组码**，不是 AWS region；
 * 签名作用域必须用官方 AWS region（NA→us-east-1、EU→eu-west-1、FE→us-west-2）。
 */
@DisplayName("P0-38 有条件签名 + P0-48 分组码→AWS region")
class SpApiConditionalSigningTest {

    private static final String HOST = "sellingpartnerapi-na.amazon.com";
    private static final String PATH = "/orders/v0/orders";
    private static final String FAKE_AK = "AKIAIOSFODNN7EXAMPLE";
    private static final String FAKE_SK = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private final AwsSigV4Signer signer = new AwsSigV4Signer();

    @Test
    @DisplayName("无 AWS 密钥：不得出现 Authorization，但保留 x-amz-date（官方未签名调用形态）")
    void unsignedWhenKeysAreNull() {
        Map<String, String> headers = signer.sign("GET", HOST, PATH, "", "", null, null, "us-east-1");

        assertFalse(headers.containsKey("Authorization"), "缺 AK/SK 时禁止签名：" + headers);
        assertTrue(headers.containsKey("x-amz-date"), "未签名调用仍需 x-amz-date：" + headers);
    }

    @Test
    @DisplayName("空白 AWS 密钥：同样不签名，且任何头值都不得包含 null / Credential=/")
    void unsignedWhenKeysAreBlank() {
        Map<String, String> h1 = signer.sign("GET", HOST, PATH, "", "", "", "", "us-east-1");
        Map<String, String> h2 = signer.sign("POST", HOST, PATH, "q=1", "{}", "   ", "   ", "us-east-1");

        for (Map<String, String> headers : List.of(h1, h2)) {
            assertFalse(headers.containsKey("Authorization"), "空白 AK/SK 禁止签名：" + headers);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                assertFalse(e.getValue() == null || e.getValue().contains("null"),
                        "头值含 null：" + e.getKey() + "=" + e.getValue());
                assertFalse(e.getValue().endsWith("Credential=/"),
                        "出现空 Credential：" + e.getValue());
            }
        }
    }

    @Test
    @DisplayName("有 AWS 密钥：仍生成 SigV4 Authorization（前向保险，非 SP-API 必需）")
    void signedWhenKeysArePresent() {
        Map<String, String> headers = signer.sign("GET", HOST, PATH, "", "", FAKE_AK, FAKE_SK, "us-east-1");

        String auth = headers.get("Authorization");
        assertNotNull(auth, "有 AK/SK 时应保留 SigV4 签名能力");
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "), auth);
        assertTrue(auth.contains("Credential=" + FAKE_AK + "/"), auth);
        assertTrue(auth.contains("SignedHeaders=host;x-amz-date"), auth);
    }

    @Test
    @DisplayName("分区码→AWS region：NA→us-east-1、EU→eu-west-1、FE→us-west-2")
    void mapsGroupCodesToAwsRegions() {
        assertEquals("us-east-1", MarketplaceRegistry.resolveAwsRegion(MarketplaceRegistry.REGION_NA));
        assertEquals("eu-west-1", MarketplaceRegistry.resolveAwsRegion(MarketplaceRegistry.REGION_EU));
        assertEquals("us-west-2", MarketplaceRegistry.resolveAwsRegion(MarketplaceRegistry.REGION_FE));
    }

    @Test
    @DisplayName("AWS region 必须出现在签名作用域内（分组码 NA 不得直接进签名）")
    void signingScopeUsesAwsRegion() {
        String awsRegion = MarketplaceRegistry.resolveAwsRegion(MarketplaceRegistry.REGION_NA);
        Map<String, String> headers = signer.sign("GET", HOST, PATH, "", "", FAKE_AK, FAKE_SK, awsRegion);

        assertTrue(headers.get("Authorization").contains("/" + awsRegion + "/execute-api/aws4_request"),
                headers.get("Authorization"));
    }

    @Test
    @DisplayName("未知 region 必须 fail-closed：不接受 AWS region 形态或脏串")
    void unknownRegionFailsClosed() {
        assertThrows(UnknownMarketplaceException.class,
                () -> MarketplaceRegistry.resolveAwsRegion("us-east-1"));
        assertThrows(UnknownMarketplaceException.class,
                () -> MarketplaceRegistry.resolveAwsRegion("NA "));
        assertThrows(UnknownMarketplaceException.class,
                () -> MarketplaceRegistry.resolveAwsRegion(null));
    }
}
