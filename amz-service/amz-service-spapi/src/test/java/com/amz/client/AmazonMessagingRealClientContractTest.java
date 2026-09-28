package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.auth.SpApiUserAgent;
import com.amz.config.SpApiConfig;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.testsupport.RecordingHttpTransport;
import com.amz.testsupport.StubCredentialStore;
import com.amz.testsupport.TestCredentials;
import com.google.gson.JsonObject;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("Amazon Messaging 官方契约（路径/方法/marketplaceIds/fail-closed）")
class AmazonMessagingRealClientContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String ORDER_ID = "123-1234567-1234567";
    private static final String TOKEN = "Atza|test-access-token";
    private static final String TOKEN_JSON =
            "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}";

    @Test
    @DisplayName("查询订单可用消息动作：GET 官方订单路径并携带 marketplaceIds")
    void getActionsUsesOfficialContract() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            return new RecordingHttpTransport.Reply(200,
                    "{\"_embedded\":{\"actions\":[{\"action\":\"confirmOrderDetails\"}]}}");
        });

        JsonObject response = client(transport).getMessagingActionsForOrder(
                SHOP_ID, ORDER_ID, MARKETPLACE_ID);

        assertTrue(response.has("_embedded"));
        HttpRequest request = transport.lastRequest();
        assertEquals("GET", request.method());
        assertEquals("/messaging/v1/orders/" + ORDER_ID, request.uri().getPath());
        assertEquals("marketplaceIds=" + MARKETPLACE_ID, request.uri().getRawQuery());
        assertEquals(TOKEN, request.headers().firstValue("x-amz-access-token").orElse(null));
        assertTrue(request.headers().firstValue("user-agent").isPresent());
    }

    @Test
    @DisplayName("九类发送动作全部使用官方路径，官方 201 才视为成功")
    void allSendActionsUseOfficialPathsAndAccept201() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            return new RecordingHttpTransport.Reply(201, "");
        });
        AmazonMessagingRealClient client = client(transport);
        JsonObject body = new JsonObject();
        body.addProperty("text", "synthetic contract payload");

        for (MessagingAction action : MessagingAction.values()) {
            client.sendMessage(SHOP_ID, ORDER_ID, MARKETPLACE_ID, action, body);
            HttpRequest request = transport.lastRequest();
            assertEquals("POST", request.method(), action.name());
            assertEquals("/messaging/v1/orders/" + ORDER_ID + "/messages/" + action.pathSegment(),
                    request.uri().getPath(), action.name());
            assertEquals("marketplaceIds=" + MARKETPLACE_ID, request.uri().getRawQuery(), action.name());
            assertTrue(RecordingHttpTransport.bodyText(request).contains("synthetic contract payload"),
                    action.name());
        }
    }

    @Test
    @DisplayName("九类发送动作 operationId 使用 messaging 命名空间，确保命中官方限流表")
    void sendActionOperationIdsUseMessagingNamespace() {
        for (MessagingAction action : MessagingAction.values()) {
            assertTrue(action.operationId().startsWith("messaging."),
                    action.name() + " 的 operationId 必须是 messaging.<官方 operationId>，实际为 "
                            + action.operationId());
        }
    }

    @Test
    @DisplayName("发送动作缺 body 时在发请求前失败，包括官方必填 body 的 invoice")
    void missingBodyFailsClosedBeforeSending() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("{}");
        AmazonMessagingRealClient client = client(transport);

        for (MessagingAction action : MessagingAction.values()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> client.sendMessage(SHOP_ID, ORDER_ID, MARKETPLACE_ID, action, null));
            assertTrue(error.getMessage().contains("body"), action.name());
        }
        assertEquals(0, transport.requestCount(), "缺 body 时不得发出 token 或业务请求");
    }

    @Test
    @DisplayName("attachments 项不符合官方 Attachment schema 时在发请求前失败")
    void invalidAttachmentFailsClosedBeforeSending() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("{}");
        AmazonMessagingRealClient client = client(transport);
        JsonObject body = new JsonObject();
        body.add("attachments", new com.google.gson.JsonArray());
        body.getAsJsonArray("attachments").add(new JsonObject());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client.sendMessage(SHOP_ID, ORDER_ID, MARKETPLACE_ID,
                        MessagingAction.INVOICE, body));

        assertTrue(error.getMessage().contains("fileName"), error.getMessage());
        assertEquals(0, transport.requestCount(), "非法附件不得发出 token 或业务请求");
    }

    @Test
    @DisplayName("marketplaceIds 缺失时在发请求前失败，不使用凭证中的默认站点兜底")
    void missingMarketplaceIdFailsClosedBeforeSending() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("{}");
        AmazonMessagingRealClient client = client(transport);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client.getMessagingActionsForOrder(SHOP_ID, ORDER_ID, " "));

        assertTrue(error.getMessage().contains("marketplaceId"), error.getMessage());
        assertEquals(0, transport.requestCount(), "缺 marketplaceId 时不得发出 token 或业务请求");
    }

    @Test
    @DisplayName("发送动作返回非 201 时抛错，不得伪装成成功")
    void sendActionFailsClosedOnNon201() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            return new RecordingHttpTransport.Reply(400, "{\"errors\":[{\"message\":\"bad request\"}]}");
        });

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client(transport).sendMessage(SHOP_ID, ORDER_ID, MARKETPLACE_ID,
                        MessagingAction.CONFIRM_ORDER_DETAILS, new JsonObject()));

        assertTrue(error.getMessage().contains("400"), error.getMessage());
    }

    private static AmazonMessagingRealClient client(RecordingHttpTransport transport) {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        config.setLwaEndpoint(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT);
        config.setBaseUrlOverride("http://127.0.0.1:18080");
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);
        SpApiRequestFactory factory = new SpApiRequestFactory(
                new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
        SpApiGateway gateway = new SpApiGateway(
                transport,
                new LwaTokenManager(transport, config, resolver),
                new StubCredentialStore().with(TestCredentials.northAmerica()),
                new SpiRateLimiter(),
                factory,
                noMetrics());
        return new AmazonMessagingRealClient(gateway);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return mock(ObjectProvider.class);
    }
}