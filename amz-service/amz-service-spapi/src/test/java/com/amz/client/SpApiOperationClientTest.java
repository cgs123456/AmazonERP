package com.amz.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("SP-API 统一 operation 执行器")
class SpApiOperationClientTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final SpApiGateway.ResolvedShop SHOP = new SpApiGateway.ResolvedShop(
            null, MARKETPLACE_ID, "NA", "https://sellingpartnerapi-na.amazon.com",
            "sellingpartnerapi-na.amazon.com", "us-east-1");

    @Test
    @DisplayName("普通 operation 通过 callJsonWithStatuses 使用目录中的方法、路径、查询和成功码")
    void normalOperationUsesCatalogContract() {
        SpApiGateway gateway = gateway();
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find("catalogItems.searchCatalogItems").orElseThrow();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("z", "a b");
        query.put("marketplaceIds", MARKETPLACE_ID);

        new RealSpApiOperationClient(gateway).execute(
                SHOP_ID, MARKETPLACE_ID, operation, Map.of(), query, null);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(gateway).callJsonWithStatuses(
                eq("GET"), same(SHOP), eq(operation.operationId()),
                eq("/catalog/2022-04-01/items"), queryCaptor.capture(), isNull(),
                eq(new int[]{200}));
        assertEquals("marketplaceIds=" + MARKETPLACE_ID + "&z=a%20b", queryCaptor.getValue());
        verify(gateway, never()).callJsonWithStatusUsingGrantlessLwa(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("7 条 grantless operation 全部通过 grantless LWA 入口且成功码来自目录")
    void allGrantlessOperationsUseGrantlessEntryPoint() {
        List<SpApiOperationSpec> grantless = SpApiOperationCatalog.operations().stream()
                .filter(SpApiOperationSpec::grantless)
                .toList();
        assertEquals(7, grantless.size());
        SpApiGateway gateway = gateway();

        for (SpApiOperationSpec operation : grantless) {
            new RealSpApiOperationClient(gateway).execute(
                    SHOP_ID, MARKETPLACE_ID, operation,
                    requiredPath(operation), requiredQuery(operation), requiredBody(operation));
            verify(gateway).callJsonWithStatusUsingGrantlessLwa(
                    eq(operation.method()), same(SHOP), eq(operation.operationId()),
                    anyString(), anyString(), any(), eq(200));
        }

        verify(gateway, never()).callJsonWithStatuses(
                anyString(), any(), anyString(), anyString(), anyString(), any(), any(int[].class));
    }

    @Test
    @DisplayName("路径参数按单个 URI path segment 编码，斜杠不能逃逸路径层级")
    void pathParametersAreEncodedAsSegments() {
        SpApiGateway gateway = gateway();
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find("listingsItems.getListingsItem").orElseThrow();
        Map<String, String> path = Map.of("sellerId", "A B/C", "sku", "S?K#1");
        Map<String, String> query = Map.of("marketplaceIds", MARKETPLACE_ID);

        new RealSpApiOperationClient(gateway).execute(
                SHOP_ID, MARKETPLACE_ID, operation, path, query, null);

        verify(gateway).callJsonWithStatuses(
                eq("GET"), same(SHOP), eq(operation.operationId()),
                eq("/listings/2021-08-01/items/A%20B%2FC/S%3FK%231"),
                eq("marketplaceIds=" + MARKETPLACE_ID), isNull(), eq(new int[]{200}));
    }

    @Test
    @DisplayName("必填 path/query/body/body 字段缺失时在任何网关交互前失败")
    void missingRequiredInputsFailBeforeGateway() {
        assertLocalFailure(
                "notifications.getSubscription", Map.of(), Map.of(), null);
        assertLocalFailure(
                "catalogItems.searchCatalogItems", Map.of(), Map.of(), null);
        assertLocalFailure(
                "listingsItems.patchListingsItem",
                Map.of("sellerId", "seller", "sku", "sku"),
                Map.of("marketplaceIds", MARKETPLACE_ID),
                null);
        assertLocalFailure(
                "listingsItems.patchListingsItem",
                Map.of("sellerId", "seller", "sku", "sku"),
                Map.of("marketplaceIds", MARKETPLACE_ID),
                bodyWith("productType", "PRODUCT"));
    }

    @Test
    @DisplayName("未知或与官方目录不一致的 operation 在出站前拒绝")
    void unknownOperationFailsBeforeGateway() {
        SpApiGateway gateway = gateway();
        SpApiOperationSpec unknown = new SpApiOperationSpec(
                "unknown.operation", "unknown", "GET", "/unknown", List.of(200), false, false,
                Set.of(), Set.of(), Set.of());

        assertThrows(IllegalArgumentException.class,
                () -> new RealSpApiOperationClient(gateway).execute(
                        SHOP_ID, MARKETPLACE_ID, unknown, Map.of(), Map.of(), null));
        verifyNoInteractions(gateway);

        SpApiOperationSpec forged = new SpApiOperationSpec(
                "catalogItems.getCatalogItem", "catalogItems", "GET", "/forged/{asin}",
                List.of(200), false, false, Set.of("asin"), Set.of("marketplaceIds"), Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> new RealSpApiOperationClient(gateway).execute(
                        SHOP_ID, MARKETPLACE_ID, forged,
                        Map.of("asin", "B000000000"), Map.of("marketplaceIds", MARKETPLACE_ID), null));
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("mock 实现使用同一校验并返回 synthetic 数据，不访问真实网关")
    void mockImplementationIsDeterministicAndSynthetic() {
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find("notifications.getDestinations").orElseThrow();
        MockSpApiOperationClient client = new MockSpApiOperationClient();

        JsonObject result = client.execute(
                SHOP_ID, MARKETPLACE_ID, operation, Map.of(), Map.of(), null);

        assertTrue(result.get("synthetic").getAsBoolean());
        // 官方 sandbox 形状：payload 顶层数组（Amazon 官方 sandbox static 响应）
        assertTrue(result.has("payload"), "官方形状必须保留 payload 顶层数组");

        assertThrows(IllegalArgumentException.class,
                () -> client.execute(SHOP_ID, MARKETPLACE_ID,
                        SpApiOperationCatalog.find("catalogItems.getCatalogItem").orElseThrow(),
                        Map.of(), Map.of("marketplaceIds", MARKETPLACE_ID), null));
    }

    private static void assertLocalFailure(String operationId,
                                           Map<String, String> path,
                                           Map<String, String> query,
                                           JsonObject body) {
        SpApiGateway gateway = gateway();
        SpApiOperationSpec operation = SpApiOperationCatalog.find(operationId).orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> new RealSpApiOperationClient(gateway).execute(
                        SHOP_ID, MARKETPLACE_ID, operation, path, query, body));
        verifyNoInteractions(gateway);
    }

    private static SpApiGateway gateway() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        when(gateway.resolveShop(SHOP_ID, MARKETPLACE_ID)).thenReturn(SHOP);
        return gateway;
    }

    private static Map<String, String> requiredPath(SpApiOperationSpec operation) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : operation.requiredPathParameters()) {
            values.put(name, "path-" + name);
        }
        return values;
    }

    private static Map<String, String> requiredQuery(SpApiOperationSpec operation) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : operation.requiredQueryParameters()) {
            values.put(name, "query-" + name);
        }
        return values;
    }

    private static JsonObject requiredBody(SpApiOperationSpec operation) {
        if (!operation.bodyRequired()) {
            return null;
        }
        JsonObject body = new JsonObject();
        for (String name : operation.requiredBodyFields()) {
            body.addProperty(name, "value-" + name);
        }
        return body;
    }

    private static JsonObject bodyWith(String name, String value) {
        JsonObject body = new JsonObject();
        body.addProperty(name, value);
        return body;
    }
}
