package com.amz.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Notifications/Listings/Pricing/Catalog/FBA Inbound 类型化客户端")
class RemainingCapabilitiesClientTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";

    @Test
    @DisplayName("Notifications 10 个方法全部映射到官方 operationId，并组装 path/query/body")
    void notificationsMethodsUseOfficialOperations() {
        RecordingClient delegate = new RecordingClient();
        NotificationsClient client = new NotificationsClient(delegate);

        client.getSubscriptions(SHOP_ID, MARKETPLACE_ID,
                List.of("ANY_OFFER_CHANGED", "ORDER_CHANGE"), "1", 25, "next");
        client.getSubscription(SHOP_ID, MARKETPLACE_ID, "ANY_OFFER_CHANGED", "1");
        client.createSubscription(SHOP_ID, MARKETPLACE_ID, "ANY_OFFER_CHANGED",
                bodyWith("destinationId", "dest-1", "payloadVersion", "1"));
        client.getSubscriptionById(SHOP_ID, MARKETPLACE_ID, "ANY_OFFER_CHANGED", "sub-1");
        client.deleteSubscriptionById(SHOP_ID, MARKETPLACE_ID, "ANY_OFFER_CHANGED", "sub-1");
        client.sendTestNotification(SHOP_ID, MARKETPLACE_ID, "ANY_OFFER_CHANGED",
                bodyWith("event", "test"));
        client.getDestinations(SHOP_ID, MARKETPLACE_ID);
        client.createDestination(SHOP_ID, MARKETPLACE_ID,
                bodyWith("name", "erp-queue", "resourceSpecification", "spec"));
        client.getDestination(SHOP_ID, MARKETPLACE_ID, "dest-1");
        client.deleteDestination(SHOP_ID, MARKETPLACE_ID, "dest-1");

        assertEquals(List.of(
                "notifications.getSubscriptions",
                "notifications.getSubscription",
                "notifications.createSubscription",
                "notifications.getSubscriptionById",
                "notifications.deleteSubscriptionById",
                "notifications.sendTestNotification",
                "notifications.getDestinations",
                "notifications.createDestination",
                "notifications.getDestination",
                "notifications.deleteDestination"), delegate.operationIds());

        Invocation subscriptions = delegate.invocation("notifications.getSubscriptions");
        assertEquals(Map.of(
                "notificationTypes", "ANY_OFFER_CHANGED,ORDER_CHANGE",
                "payloadVersion", "1",
                "pageSize", "25",
                "nextToken", "next"), subscriptions.query());

        Invocation create = delegate.invocation("notifications.createSubscription");
        assertEquals(Map.of("notificationType", "ANY_OFFER_CHANGED"), create.path());
        assertEquals("dest-1", create.body().get("destinationId").getAsString());
    }

    @Test
    @DisplayName("Listings Items 5 个方法保留必填 marketplaceIds 并传递官方 body")
    void listingsMethodsUseOfficialOperations() {
        RecordingClient delegate = new RecordingClient();
        ListingsItemsClient client = new ListingsItemsClient(delegate);
        Map<String, String> optional = Map.of("includedData", "summaries");

        client.deleteListingsItem(SHOP_ID, MARKETPLACE_ID, "seller", "sku", List.of(MARKETPLACE_ID), optional);
        client.getListingsItem(SHOP_ID, MARKETPLACE_ID, "seller", "sku", List.of(MARKETPLACE_ID), optional);
        client.patchListingsItem(SHOP_ID, MARKETPLACE_ID, "seller", "sku",
                List.of(MARKETPLACE_ID), bodyWith("productType", "PRODUCT", "patches", "patch"));
        client.putListingsItem(SHOP_ID, MARKETPLACE_ID, "seller", "sku",
                List.of(MARKETPLACE_ID), bodyWith("productType", "PRODUCT", "attributes", "attrs"));
        client.searchListingsItems(SHOP_ID, MARKETPLACE_ID, "seller",
                List.of(MARKETPLACE_ID), optional);

        assertEquals(List.of(
                "listingsItems.deleteListingsItem",
                "listingsItems.getListingsItem",
                "listingsItems.patchListingsItem",
                "listingsItems.putListingsItem",
                "listingsItems.searchListingsItems"), delegate.operationIds());

        Invocation get = delegate.invocation("listingsItems.getListingsItem");
        assertEquals(Map.of("sellerId", "seller", "sku", "sku"), get.path());
        assertEquals(Map.of("marketplaceIds", MARKETPLACE_ID, "includedData", "summaries"), get.query());

        Invocation patch = delegate.invocation("listingsItems.patchListingsItem");
        assertEquals("PRODUCT", patch.body().get("productType").getAsString());
    }

    @Test
    @DisplayName("Product Pricing 与 Catalog Items 方法映射到官方 operationId")
    void pricingAndCatalogMethodsUseOfficialOperations() {
        RecordingClient delegate = new RecordingClient();
        ProductPricingClient pricing = new ProductPricingClient(delegate);
        CatalogItemsClient catalog = new CatalogItemsClient(delegate);

        pricing.getFeaturedOfferExpectedPriceBatch(SHOP_ID, MARKETPLACE_ID,
                bodyWith("requests", "featured"));
        pricing.getCompetitiveSummary(SHOP_ID, MARKETPLACE_ID,
                bodyWith("requests", "summary"));
        catalog.searchCatalogItems(SHOP_ID, MARKETPLACE_ID,
                List.of(MARKETPLACE_ID), Map.of("pageSize", "10"));
        catalog.getCatalogItem(SHOP_ID, MARKETPLACE_ID, "B000000000",
                List.of(MARKETPLACE_ID), Map.of("includedData", "summaries"));

        assertEquals(List.of(
                "productPricing.getFeaturedOfferExpectedPriceBatch",
                "productPricing.getCompetitiveSummary",
                "catalogItems.searchCatalogItems",
                "catalogItems.getCatalogItem"), delegate.operationIds());

        Invocation getCatalog = delegate.invocation("catalogItems.getCatalogItem");
        assertEquals(Map.of("asin", "B000000000"), getCatalog.path());
        assertEquals(Map.of("marketplaceIds", MARKETPLACE_ID, "includedData", "summaries"),
                getCatalog.query());
    }

    @Test
    @DisplayName("FBA Inbound 暴露 45 条目录并提供仅限该家族的通用执行入口")
    void fbaInboundExposesCatalogAndGuardedExecute() {
        RecordingClient delegate = new RecordingClient();
        FbaInboundClient client = new FbaInboundClient(delegate);

        assertEquals(45, client.operations().size());
        assertEquals(45, client.operations().stream()
                .filter(operation -> "fbaInbound".equals(operation.family())).count());

        client.execute(SHOP_ID, MARKETPLACE_ID, "fbaInbound.listInboundPlans",
                Map.of(), Map.of(), null);
        assertEquals(List.of("fbaInbound.listInboundPlans"), delegate.operationIds());

        assertThrows(IllegalArgumentException.class,
                () -> client.execute(SHOP_ID, MARKETPLACE_ID,
                        "catalogItems.searchCatalogItems", Map.of(),
                        Map.of("marketplaceIds", MARKETPLACE_ID), null));
    }

    @Test
    @DisplayName("mock profile 下五个业务客户端均可使用同一 synthetic 执行器")
    void allClientsWorkWithMockOperationClient() {
        MockSpApiOperationClient delegate = new MockSpApiOperationClient();

        assertSynthetic(new NotificationsClient(delegate)
                .getDestinations(SHOP_ID, MARKETPLACE_ID));
        assertSynthetic(new ListingsItemsClient(delegate)
                .searchListingsItems(SHOP_ID, MARKETPLACE_ID, "seller",
                        List.of(MARKETPLACE_ID), Map.of()));
        assertSynthetic(new ProductPricingClient(delegate)
                .getCompetitiveSummary(SHOP_ID, MARKETPLACE_ID, bodyWith("requests", "mock")));
        assertSynthetic(new CatalogItemsClient(delegate)
                .searchCatalogItems(SHOP_ID, MARKETPLACE_ID, List.of(MARKETPLACE_ID), Map.of()));
        assertSynthetic(new FbaInboundClient(delegate)
                .execute(SHOP_ID, MARKETPLACE_ID, "fbaInbound.listInboundPlans",
                        Map.of(), Map.of(), null));
    }

    private static void assertSynthetic(JsonObject response) {
        assertTrue(response.get("synthetic").getAsBoolean());
    }

    private static JsonObject bodyWith(String name, String value) {
        JsonObject body = new JsonObject();
        body.addProperty(name, value);
        return body;
    }

    private static JsonObject bodyWith(String first, String firstValue, String second, String secondValue) {
        JsonObject body = bodyWith(first, firstValue);
        body.addProperty(second, secondValue);
        return body;
    }

    private record Invocation(SpApiOperationSpec operation,
                              Map<String, String> path,
                              Map<String, String> query,
                              JsonObject body) {
    }

    private static final class RecordingClient implements SpApiOperationClient {
        private final List<Invocation> invocations = new ArrayList<>();

        @Override
        public JsonObject execute(Long shopId,
                                  String marketplaceId,
                                  SpApiOperationSpec operation,
                                  Map<String, String> pathParameters,
                                  Map<String, String> queryParameters,
                                  JsonObject body) {
            invocations.add(new Invocation(
                    operation,
                    pathParameters == null ? Map.of() : Map.copyOf(pathParameters),
                    queryParameters == null ? Map.of() : Map.copyOf(queryParameters),
                    body));
            JsonObject response = new JsonObject();
            response.addProperty("synthetic", true);
            return response;
        }

        List<String> operationIds() {
            return invocations.stream().map(i -> i.operation().operationId()).toList();
        }

        Invocation invocation(String operationId) {
            return invocations.stream()
                    .filter(i -> operationId.equals(i.operation().operationId()))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
