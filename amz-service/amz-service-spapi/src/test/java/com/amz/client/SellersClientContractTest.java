package com.amz.client;

import com.amz.connector.SpApiCallException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Sellers marketplaceParticipations 官方契约")
class SellersClientContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String OPERATION_ID = "sellers.getMarketplaceParticipations";
    private static final String PATH = "/sellers/v1/marketplaceParticipations";

    private static final String VALID_RESPONSE = """
            {
              "payload": [
                {
                  "marketplace": {
                    "id": "ATVPDKIKX0DER",
                    "name": "Amazon.com",
                    "countryCode": "US",
                    "defaultCurrencyCode": "USD",
                    "defaultLanguageCode": "en_US",
                    "domainName": "www.amazon.com"
                  },
                  "storeName": "BestSellerStore",
                  "participation": {
                    "isParticipating": true,
                    "hasSuspendedListings": false
                  }
                }
              ]
            }
            """;

    @Test
    @DisplayName("使用官方 GET 路径与 operationId，并保留 payload")
    void usesOfficialContract() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        SpApiGateway.ResolvedShop shop = resolvedShop();
        JsonObject expected = json(VALID_RESPONSE);
        when(gateway.resolveShop(SHOP_ID, MARKETPLACE_ID)).thenReturn(shop);
        when(gateway.callJsonWithStatus("GET", shop, OPERATION_ID, PATH,
                null, null, 200)).thenReturn(expected);

        JsonObject actual = new SellersClient(gateway)
                .getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID);

        assertSame(expected, actual);
        verify(gateway).resolveShop(SHOP_ID, MARKETPLACE_ID);
        verify(gateway).callJsonWithStatus(eq("GET"), eq(shop), eq(OPERATION_ID),
                eq(PATH), eq(null), eq(null), eq(200));
    }

    @Test
    @DisplayName("payload 缺失或类型错误时显式失败")
    void invalidPayloadFailsExplicitly() {
        assertInvalid("{}", "payload");
        assertInvalid("{\"payload\":{}}", "payload");
    }

    @Test
    @DisplayName("空数组是官方 schema 允许的无参与市场结果，不伪装成结构损坏")
    void emptyPayloadIsValid() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        SpApiGateway.ResolvedShop shop = resolvedShop();
        JsonObject expected = json("{\"payload\":[]}");
        when(gateway.resolveShop(SHOP_ID, MARKETPLACE_ID)).thenReturn(shop);
        when(gateway.callJsonWithStatus("GET", shop, OPERATION_ID, PATH,
                null, null, 200)).thenReturn(expected);

        JsonObject actual = new SellersClient(gateway)
                .getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID);

        assertSame(expected, actual);
    }

    @Test
    @DisplayName("marketplace 与 participation 的官方必填字段缺失或类型错误时显式失败")
    void invalidRequiredFieldsFailExplicitly() {
        assertInvalid(responseWithPayload("{\"storeName\":\"BestSellerStore\","
                + "\"participation\":{\"isParticipating\":true,\"hasSuspendedListings\":false}}"), "marketplace");
        assertInvalid(responseWithPayload("{\"marketplace\":{},"
                + "\"storeName\":\"BestSellerStore\","
                + "\"participation\":{\"isParticipating\":true,\"hasSuspendedListings\":false}}"), "id");
        assertInvalid(responseWithPayload("{\"marketplace\":{"
                + "\"id\":\"ATVPDKIKX0DER\",\"name\":\"Amazon.com\",\"countryCode\":\"US\","
                + "\"defaultCurrencyCode\":\"USD\",\"defaultLanguageCode\":\"en_US\","
                + "\"domainName\":\"www.amazon.com\"},"
                + "\"participation\":{\"isParticipating\":true,\"hasSuspendedListings\":false}}"), "storeName");
        assertInvalid(responseWithPayload("{\"marketplace\":{"
                + "\"id\":\"ATVPDKIKX0DER\",\"name\":\"Amazon.com\",\"countryCode\":\"US\","
                + "\"defaultCurrencyCode\":\"USD\",\"defaultLanguageCode\":\"en_US\","
                + "\"domainName\":\"www.amazon.com\"},\"storeName\":\"BestSellerStore\"}"), "participation");
        assertInvalid(responseWithPayload("{\"marketplace\":{"
                + "\"id\":\"ATVPDKIKX0DER\",\"name\":\"Amazon.com\",\"countryCode\":\"US\","
                + "\"defaultCurrencyCode\":\"USD\",\"defaultLanguageCode\":\"en_US\","
                + "\"domainName\":\"www.amazon.com\"},\"storeName\":\"BestSellerStore\","
                + "\"participation\":{\"isParticipating\":\"true\",\"hasSuspendedListings\":false}}"),
                "isParticipating");
    }

    @Test
    @DisplayName("403 / 429 / 5xx 等网关失败原样上抛，不转换成成功")
    void gatewayFailuresArePropagated() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        SpApiGateway.ResolvedShop shop = resolvedShop();
        when(gateway.resolveShop(SHOP_ID, MARKETPLACE_ID)).thenReturn(shop);
        for (int status : new int[]{403, 429, 500}) {
            SpApiCallException failure = SpApiCallException.statusFailure(OPERATION_ID, PATH, status);
            doThrow(failure).when(gateway).callJsonWithStatus(
                    "GET", shop, OPERATION_ID, PATH, null, null, 200);

            SpApiCallException actual = assertThrows(SpApiCallException.class,
                    () -> new SellersClient(gateway).getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID));

            assertSame(failure, actual);
        }
    }

    @Test
    @DisplayName("mock profile 下不得装配真实 Sellers 客户端")
    void realClientIsDisabledInMockProfile() {
        Profile profile = SellersClient.class.getAnnotation(Profile.class);
        assertTrue(profile != null, "SellersClient 必须声明 Profile，避免 mock 环境伪造真实调用能力");
        assertEquals(java.util.List.of("!mock"), java.util.List.of(profile.value()));
    }

    private static void assertInvalid(String response, String diagnosticFragment) {
        SpApiGateway gateway = mock(SpApiGateway.class);
        SpApiGateway.ResolvedShop shop = resolvedShop();
        when(gateway.resolveShop(SHOP_ID, MARKETPLACE_ID)).thenReturn(shop);
        when(gateway.callJsonWithStatus("GET", shop,
                OPERATION_ID, PATH, null, null, 200)).thenReturn(json(response));

        SpApiCallException error = assertThrows(SpApiCallException.class,
                () -> new SellersClient(gateway).getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID));

        assertEquals(OPERATION_ID, error.getOperationId());
        assertEquals(PATH, error.getPath());
        assertEquals(0, error.getPlatformStatus(), "响应结构错误是本地契约失败，不是平台 HTTP 状态");
        assertTrue(error.getDiagnostic().contains(diagnosticFragment), error.getDiagnostic());
    }

    private static String responseWithPayload(String item) {
        return "{\"payload\":[" + item + "]}";
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static SpApiGateway.ResolvedShop resolvedShop() {
        return new SpApiGateway.ResolvedShop(null, MARKETPLACE_ID, "NA",
                "https://sellingpartnerapi-na.amazon.com",
                "sellingpartnerapi-na.amazon.com", "us-east-1");
    }
}
