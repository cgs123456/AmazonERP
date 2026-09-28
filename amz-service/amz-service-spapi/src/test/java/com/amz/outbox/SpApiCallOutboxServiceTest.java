package com.amz.outbox;

import com.amz.connector.RestrictedResource;
import com.amz.connector.SpApiTokenSource;
import com.amz.mapper.SpApiCallOutboxMapper;
import com.amz.model.SpApiCallOutboxEntity;
import com.amz.util.CryptoUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Outbox 持久化契约：多成功码、限流分档与重放请求可逆")
class SpApiCallOutboxServiceTest {

    @Test
    @DisplayName("begin 保存全部成功状态码与限流分档，而非只保存第一个")
    void beginPersistsAllExpectedStatusesAndVariant() {
        SpApiCallOutboxMapper mapper = mock(SpApiCallOutboxMapper.class);
        CryptoUtil crypto = crypto();
        when(mapper.insert(any(SpApiCallOutboxEntity.class))).thenAnswer(invocation -> {
            SpApiCallOutboxEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return 1;
        });
        SpApiCallOutboxService service = new SpApiCallOutboxService(mapper, crypto, 4, 60);

        Long id = service.begin(SpApiCallOutboxService.CallRequest.of(
                1001L, "ATVPDKIKX0DER", "feeds.createFeed", "POST",
                "/feeds/2021-06-30/feeds", null, "{\"header\":{}}",
                List.of(200, 202), "JSON_LISTINGS_FEED"));

        assertEquals(1L, id);
        SpApiCallOutboxEntity saved = (SpApiCallOutboxEntity) org.mockito.Mockito.mockingDetails(mapper)
                .getInvocations().stream()
                .filter(invocation -> "insert".equals(invocation.getMethod().getName()))
                .findFirst().orElseThrow().getArgument(0);
        assertEquals("200,202", saved.getExpectedStatuses());
        assertEquals("JSON_LISTINGS_FEED", saved.getRateLimitVariant());
        assertEquals("enc:{\"header\":{}}", saved.getRequestBodyEncrypted());
    }

    @Test
    @DisplayName("loadForReplay 完整还原多成功码、限流分档与加密请求体")
    void loadForReplayRestoresCall() {
        SpApiCallOutboxMapper mapper = mock(SpApiCallOutboxMapper.class);
        CryptoUtil crypto = crypto();
        SpApiCallOutboxEntity entity = new SpApiCallOutboxEntity();
        entity.setId(2L);
        entity.setShopId(1001L);
        entity.setMarketplaceId("ATVPDKIKX0DER");
        entity.setOperationId("feeds.createFeed");
        entity.setHttpMethod("POST");
        entity.setRequestPath("/feeds/2021-06-30/feeds");
        entity.setRequestQuery(null);
        entity.setRequestBodyEncrypted("enc:{\"header\":{}}");
        entity.setExpectedStatuses("200,202");
        entity.setRateLimitVariant("JSON_LISTINGS_FEED");
        entity.setStatus(SpApiCallOutboxService.DLQ);
        when(mapper.selectById(2L)).thenReturn(entity);
        SpApiCallOutboxService service = new SpApiCallOutboxService(mapper, crypto, 4, 60);

        SpApiCallOutboxService.ReplayCall call = service.loadForReplay(2L);

        assertEquals(List.of(200, 202), call.expectedStatuses());
        assertEquals("JSON_LISTINGS_FEED", call.rateLimitVariant());
        assertEquals("{\"header\":{}}", call.body());
        assertEquals(SpApiCallOutboxService.DLQ, call.originalStatus());
    }

    @Test
    @DisplayName("begin 对 RDT 调用只保存加密受限资源、摘要和数量，不保存 RDT 本体")
    void beginPersistsRestrictedResourceMetadata() {
        SpApiCallOutboxMapper mapper = mock(SpApiCallOutboxMapper.class);
        CryptoUtil crypto = crypto();
        when(mapper.insert(any(SpApiCallOutboxEntity.class))).thenAnswer(invocation -> {
            SpApiCallOutboxEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        });
        SpApiCallOutboxService service = new SpApiCallOutboxService(mapper, crypto, 4, 60);
        List<RestrictedResource> resources = List.of(
                new RestrictedResource("GET", "/orders/v0/orders/123-1234567-1234567",
                        List.of("buyerInfo")));

        Long id = service.begin(SpApiCallOutboxService.CallRequest.of(
                1001L, "ATVPDKIKX0DER", "orders.getOrders", "GET",
                "/orders/v0/orders", "MarketplaceIds=ATVPDKIKX0DER", null,
                List.of(200), null, SpApiTokenSource.RDT, resources));

        assertEquals(11L, id);
        SpApiCallOutboxEntity saved = (SpApiCallOutboxEntity) org.mockito.Mockito.mockingDetails(mapper)
                .getInvocations().stream()
                .filter(invocation -> "insert".equals(invocation.getMethod().getName()))
                .findFirst().orElseThrow().getArgument(0);
        assertEquals("RDT", saved.getTokenSource());
        assertEquals(RestrictedResource.canonicalKey(resources), saved.getRestrictedResourceHash());
        assertEquals(1, saved.getRestrictedResourceCount());
        assertTrue(saved.getRestrictedResourcesEncrypted().startsWith("enc:"));
        assertTrue(saved.getRestrictedResourcesEncrypted().contains("restrictedResources"));
        assertFalse(saved.getRestrictedResourcesEncrypted().contains("restrictedDataToken"));
    }

    @Test
    @DisplayName("loadForReplay 完整还原 RDT token source 与受限资源")
    void loadForReplayRestoresRestrictedResources() {
        SpApiCallOutboxMapper mapper = mock(SpApiCallOutboxMapper.class);
        CryptoUtil crypto = crypto();
        SpApiCallOutboxEntity entity = new SpApiCallOutboxEntity();
        entity.setId(12L);
        entity.setShopId(1001L);
        entity.setMarketplaceId("ATVPDKIKX0DER");
        entity.setOperationId("orders.getOrders");
        entity.setHttpMethod("GET");
        entity.setRequestPath("/orders/v0/orders");
        entity.setRequestQuery("MarketplaceIds=ATVPDKIKX0DER");
        entity.setExpectedStatuses("200");
        entity.setTokenSource("RDT");
        entity.setRestrictedResourceCount(1);
        entity.setRestrictedResourceHash(RestrictedResource.canonicalKey(List.of(
                new RestrictedResource("GET", "/orders/v0/orders/123-1234567-1234567",
                        List.of("buyerInfo")))));
        entity.setRestrictedResourcesEncrypted("enc:{\"restrictedResources\":[{\"method\":\"GET\",\"path\":\"/orders/v0/orders/123-1234567-1234567\",\"dataElements\":[\"buyerInfo\"]}]}");
        entity.setStatus(SpApiCallOutboxService.DLQ);
        when(mapper.selectById(12L)).thenReturn(entity);
        SpApiCallOutboxService service = new SpApiCallOutboxService(mapper, crypto, 4, 60);

        SpApiCallOutboxService.ReplayCall call = service.loadForReplay(12L);

        assertEquals(SpApiTokenSource.RDT, call.tokenSource());
        assertEquals(List.of(new RestrictedResource("GET", "/orders/v0/orders/123-1234567-1234567",
                List.of("buyerInfo"))), call.restrictedResources());
    }

    @Test
    @DisplayName("旧 Outbox 记录缺少 token_source 时按 LWA 兼容读取")
    void oldRecordDefaultsToLwa() {
        SpApiCallOutboxMapper mapper = mock(SpApiCallOutboxMapper.class);
        SpApiCallOutboxEntity entity = new SpApiCallOutboxEntity();
        entity.setId(13L);
        entity.setShopId(1001L);
        entity.setMarketplaceId("ATVPDKIKX0DER");
        entity.setOperationId("orders.getOrders");
        entity.setHttpMethod("GET");
        entity.setRequestPath("/orders/v0/orders");
        entity.setExpectedStatuses("200");
        entity.setStatus(SpApiCallOutboxService.DLQ);
        when(mapper.selectById(13L)).thenReturn(entity);
        SpApiCallOutboxService service = new SpApiCallOutboxService(mapper, crypto(), 4, 60);

        SpApiCallOutboxService.ReplayCall call = service.loadForReplay(13L);

        assertEquals(SpApiTokenSource.LWA, call.tokenSource());
        assertEquals(List.of(), call.restrictedResources());
    }

    @Test
    @DisplayName("安全视图不含请求正文、查询串或任何密文字段")
    void outboxViewHasNoSensitivePayload() {
        SpApiCallOutboxEntity entity = new SpApiCallOutboxEntity();
        entity.setId(3L);
        entity.setShopId(1001L);
        entity.setOperationId("orders.getOrders");
        entity.setHttpMethod("GET");
        entity.setRequestPath("/orders/v0/orders");
        entity.setRequestQuery("MarketplaceIds=ATVPDKIKX0DER");
        entity.setRequestBodyEncrypted("enc:secret-request");
        entity.setResponseBodyEncrypted("enc:secret-response");
        entity.setStatus(SpApiCallOutboxService.DLQ);
        entity.setExpectedStatuses("200");
        entity.setTokenSource("RDT");
        entity.setRestrictedResourceCount(1);
        entity.setRestrictedResourceHash(RestrictedResource.canonicalKey(List.of(
                new RestrictedResource("GET", "/orders/v0/orders/123-1234567-1234567",
                        List.of("buyerInfo")))));

        SpApiCallOutboxService.OutboxView view = SpApiCallOutboxService.OutboxView.from(entity);

        assertEquals(3L, view.id());
        assertEquals(List.of(200), view.expectedStatuses());
        assertEquals(SpApiTokenSource.RDT, view.tokenSource());
        assertEquals(1, view.restrictedResourceCount());
        assertFalse(view.toString().contains("/orders/v0/orders/123-1234567-1234567"));
        assertNull(view.responseRequestId());
    }

    @Test
    @DisplayName("非法状态在查询数据库前显式失败，不能伪装为空列表")
    void invalidStatusFailsBeforeQuery() {
        SpApiCallOutboxMapper mapper = mock(SpApiCallOutboxMapper.class);
        SpApiCallOutboxService service = new SpApiCallOutboxService(mapper, crypto(), 4, 60);

        assertThrows(IllegalArgumentException.class,
                () -> service.list("BOGUS", 50, List.of(1001L)));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("begin 对 grantless 调用保存 LWA_GRANTLESS，且不保存受限资源元数据")
    void beginPersistsGrantlessWithoutRestrictedMetadata() {
        SpApiCallOutboxMapper mapper = mock(SpApiCallOutboxMapper.class);
        CryptoUtil crypto = crypto();
        when(mapper.insert(any(SpApiCallOutboxEntity.class))).thenAnswer(invocation -> {
            SpApiCallOutboxEntity entity = invocation.getArgument(0);
            entity.setId(21L);
            return 1;
        });
        SpApiCallOutboxService service = new SpApiCallOutboxService(mapper, crypto, 4, 60);

        Long id = service.begin(SpApiCallOutboxService.CallRequest.of(
                1001L, "ATVPDKIKX0DER", "notifications.getDestinations", "GET",
                "/notifications/v1/destinations", null, null,
                List.of(200), null, SpApiTokenSource.LWA_GRANTLESS, List.of()));

        assertEquals(21L, id);
        SpApiCallOutboxEntity saved = (SpApiCallOutboxEntity) org.mockito.Mockito.mockingDetails(mapper)
                .getInvocations().stream()
                .filter(invocation -> "insert".equals(invocation.getMethod().getName()))
                .findFirst().orElseThrow().getArgument(0);
        assertEquals("LWA_GRANTLESS", saved.getTokenSource());
        assertNull(saved.getRestrictedResourcesEncrypted());
        assertNull(saved.getRestrictedResourceHash());
        assertNull(saved.getRestrictedResourceCount());
    }

    @Test
    @DisplayName("loadForReplay 还原 LWA_GRANTLESS，并保持无受限资源语义")
    void loadForReplayRestoresGrantlessSource() {
        SpApiCallOutboxMapper mapper = mock(SpApiCallOutboxMapper.class);
        SpApiCallOutboxEntity entity = new SpApiCallOutboxEntity();
        entity.setId(22L);
        entity.setShopId(1001L);
        entity.setMarketplaceId("ATVPDKIKX0DER");
        entity.setOperationId("notifications.getDestinations");
        entity.setHttpMethod("GET");
        entity.setRequestPath("/notifications/v1/destinations");
        entity.setExpectedStatuses("200");
        entity.setTokenSource("LWA_GRANTLESS");
        entity.setStatus(SpApiCallOutboxService.DLQ);
        when(mapper.selectById(22L)).thenReturn(entity);
        SpApiCallOutboxService service = new SpApiCallOutboxService(mapper, crypto(), 4, 60);

        SpApiCallOutboxService.ReplayCall call = service.loadForReplay(22L);

        assertEquals(SpApiTokenSource.LWA_GRANTLESS, call.tokenSource());
        assertEquals(List.of(), call.restrictedResources());
    }

    @Test
    @DisplayName("LWA_GRANTLESS 不得持久化受限资源元数据")
    void grantlessRejectsRestrictedResources() {
        SpApiCallOutboxService service = new SpApiCallOutboxService(
                mock(SpApiCallOutboxMapper.class), crypto(), 4, 60);

        assertThrows(IllegalArgumentException.class, () -> service.begin(
                SpApiCallOutboxService.CallRequest.of(
                        1001L, "ATVPDKIKX0DER", "notifications.getDestinations", "GET",
                        "/notifications/v1/destinations", null, null,
                        List.of(200), null, SpApiTokenSource.LWA_GRANTLESS,
                        List.of(new RestrictedResource("GET", "/orders/v0/orders", List.of("buyerInfo"))))));
    }
    private static CryptoUtil crypto() {
        CryptoUtil crypto = mock(CryptoUtil.class);
        when(crypto.encrypt(anyString())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        when(crypto.decrypt(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).substring("enc:".length()));
        return crypto;
    }
}
