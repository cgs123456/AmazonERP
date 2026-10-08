package com.amz.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock SP-API 客户端形状契约：全部 64 个 catalog operation 的响应必须与
 * 生成器产出的官方形状 fixture 完全一致。
 *
 * <p>形状来源（离线、非自造）：Amazon 官方模型里内嵌的
 * {@code x-amzn-api-sandbox.static} 响应（Amazon 自己发布的沙箱响应）；
 * 无 sandbox 块的 operation 用官方 response schema 推最小合法形状。
 * 生成器：{@code tools/contract-fixtures/generate_b_bucket_fixtures.py}。</p>
 */
@DisplayName("Mock SP-API 全 operation 官方形状契约")
class MockSpApiOperationClientShapeTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";

    private final MockSpApiOperationClient client = new MockSpApiOperationClient();

    @Test
    @DisplayName("fixture 覆盖全部 catalog operation 且每个都带 synthetic 标记")
    void fixtureCoversEveryCatalogOperation() throws Exception {
        Map<String, JsonObject> fixtures = fixtures();
        for (SpApiOperationSpec operation : SpApiOperationCatalog.operations()) {
            JsonObject payload = fixtures.get(operation.operationId());
            assertNotNull(payload, "fixture 缺 operation：" + operation.operationId());
            assertTrue(payload.has("synthetic") && payload.get("synthetic").getAsBoolean(),
                    "fixture 丢失 synthetic 标记：" + operation.operationId());
        }
        assertEquals(SpApiOperationCatalog.operations().size(), fixtures.size(),
                "fixture 数量必须与 catalog 一致，防 catalog 收缩后 fixture 腐化");
    }

    @Test
    @DisplayName("全部 64 个 operation 执行结果与官方形状 fixture 逐字段一致且确定性")
    void everyOperationReturnsOfficialShapeDeterministically() throws Exception {
        for (SpApiOperationSpec operation : SpApiOperationCatalog.operations()) {
            Map<String, String> path = requiredPath(operation);
            Map<String, String> query = requiredQuery(operation);
            JsonObject body = requiredBody(operation);

            JsonObject first = client.execute(SHOP_ID, MARKETPLACE_ID, operation, path, query, body);
            JsonObject second = client.execute(SHOP_ID, MARKETPLACE_ID, operation, path, query, body);
            JsonObject expected = fixtures().get(operation.operationId());

            assertEquals(expected, first, "形状必须等于官方 fixture：" + operation.operationId());
            assertEquals(first, second, "必须确定性：" + operation.operationId());
        }
    }

    @Test
    @DisplayName("fixture 未覆盖的 operation 必须显性失败（fail-closed，不退回自造 envelope）")
    void missingFixtureFailsClosed() {
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find("notifications.getDestinations").orElseThrow();
        MockSpApiOperationClient emptyClient = new MockSpApiOperationClient(Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> emptyClient.execute(SHOP_ID, MARKETPLACE_ID, operation, Map.of(), Map.of(), null));
    }

    @Test
    @DisplayName("provenance 记录与 fixture 一致（来源可审计）")
    void provenanceDigestIsRecorded() throws Exception {
        String provenance = resourceText("/mock/provenance.json");
        assertTrue(provenance.contains("x-amzn-api-sandbox"),
                "provenance 必须注明官方 sandbox 来源");
        assertTrue(provenance.contains("generate_b_bucket_fixtures.py"));
    }

    private static Map<String, JsonObject> fixtures() throws Exception {
        String json = resourceText("/mock/fixtures.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
            out.put(e.getKey(), e.getValue().getAsJsonObject());
        }
        return out;
    }

    private static String resourceText(String resource) throws Exception {
        try (InputStream in = MockSpApiOperationClientShapeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "resource missing: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
}
