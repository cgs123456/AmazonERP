package com.amz.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SP-API 凭证结构完整性校验（fail-closed）")
class ShopCredentialValidatorTest {

    @Test
    @DisplayName("marketplaceId 合法且 LWA 三要素齐全时通过，AWS 密钥可省略")
    void acceptsMarketplaceWithoutAwsKeys() {
        ShopCredential credential = credential();
        credential.setRegion(null);

        assertTrue(ShopCredentialValidator.validate(credential).isEmpty());
    }

    @Test
    @DisplayName("缺少 marketplaceId 时可用合法 region 路由，但仍要求 LWA 三要素")
    void acceptsRegionFallback() {
        ShopCredential credential = credential();
        credential.setMarketplaceId(null);
        credential.setRegion("NA");

        assertTrue(ShopCredentialValidator.validate(credential).isEmpty());
    }

    @Test
    @DisplayName("LWA clientId/clientSecret/refreshToken 任一缺失都必须拒绝")
    void rejectsMissingLwaFields() {
        ShopCredential credential = credential();
        credential.setClientId(" ");
        credential.setClientSecret(null);
        credential.setRefreshToken("");

        List<String> problems = ShopCredentialValidator.validate(credential);

        assertTrue(problems.contains("clientId"));
        assertTrue(problems.contains("clientSecret"));
        assertTrue(problems.contains("refreshToken"));
    }

    @Test
    @DisplayName("marketplaceId 与 region 同时缺失时必须拒绝")
    void rejectsMissingRoute() {
        ShopCredential credential = credential();
        credential.setMarketplaceId(null);
        credential.setRegion(null);

        List<String> problems = ShopCredentialValidator.validate(credential);

        assertTrue(problems.contains("marketplaceId/region"));
    }

    @Test
    @DisplayName("未登记 marketplaceId 必须拒绝，不能静默回落到 NA")
    void rejectsUnknownMarketplace() {
        ShopCredential credential = credential();
        credential.setMarketplaceId("NOT-A-MARKETPLACE");

        List<String> problems = ShopCredentialValidator.validate(credential);

        assertTrue(problems.contains("marketplaceId"));
    }

    @Test
    @DisplayName("region 与 marketplaceId 映射不一致必须拒绝")
    void rejectsMismatchedRegion() {
        ShopCredential credential = credential();
        credential.setMarketplaceId("ATVPDKIKX0DER");
        credential.setRegion("EU");

        List<String> problems = ShopCredentialValidator.validate(credential);

        assertTrue(problems.contains("region"));
    }

    @Test
    @DisplayName("AWS accessKey/secretKey 只能同时存在或同时省略")
    void rejectsPartialAwsKeyPair() {
        ShopCredential credential = credential();
        credential.setAccessKey("AKIAIOSFODNN7EXAMPLE");

        List<String> problems = ShopCredentialValidator.validate(credential);

        assertTrue(problems.contains("accessKey/secretKey"));
        assertFalse(problems.contains("clientId"));
    }

    private static ShopCredential credential() {
        ShopCredential credential = new ShopCredential();
        credential.setShopId(1001L);
        credential.setClientId("amzn1.application-oa2-client.test");
        credential.setClientSecret("test-client-secret");
        credential.setRefreshToken("Atzr|test-refresh-token");
        credential.setMarketplaceId("ATVPDKIKX0DER");
        credential.setRegion("NA");
        return credential;
    }
}