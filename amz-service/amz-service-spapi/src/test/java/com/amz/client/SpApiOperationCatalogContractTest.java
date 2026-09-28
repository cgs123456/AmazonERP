package com.amz.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SP-API 剩余能力官方 operation 目录契约")
class SpApiOperationCatalogContractTest {

    private record Snapshot(String resource, String family, long bytes, String sha256) {
    }

    private static final List<Snapshot> SNAPSHOTS = List.of(
            new Snapshot("/contracts/notifications.json", "notifications", 95405L,
                    "6a5e945f2a53a91b9b97c27cd4570dc399db3dde2b777f623fc226fbcdc8469a"),
            new Snapshot("/contracts/listingsItems_2021-08-01.json", "listingsItems", 157514L,
                    "117617f4c86dbd5c1708913103806a24c0ef0bbcfb6054e415d044d07761faeb"),
            new Snapshot("/contracts/productPricing_2022-05-01.json", "productPricing", 105753L,
                    "db6ffeab130bf1d4ab8fa47e4e83417d30d9cb682b3ce53f74ed51b62f6f817c"),
            new Snapshot("/contracts/catalogItems_2022-04-01.json", "catalogItems", 151872L,
                    "1a029b01df1d847d3057740a6e877f89f2ab78104b5b4d8f4b839f8d00f600c2"),
            new Snapshot("/contracts/fulfillmentInbound_2024-03-20.json", "fbaInbound", 560644L,
                    "a4d4cdd08dd3f381f27154d7f9f503d45e0486d416c629598341bbd23c7ff487"));

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete");

    private static final Set<String> NOTIFICATIONS_GRANTLESS = Set.of(
            "notifications.getSubscriptionById",
            "notifications.deleteSubscriptionById",
            "notifications.sendTestNotification",
            "notifications.getDestinations",
            "notifications.createDestination",
            "notifications.getDestination",
            "notifications.deleteDestination");

    @Test
    @DisplayName("64 条 operation 的方法、路径、成功码、鉴权边界和必填参数逐条来自官方快照")
    void catalogMatchesOfficialSnapshots() throws Exception {
        List<SpApiOperationSpec> expected = new ArrayList<>();
        for (Snapshot snapshot : SNAPSHOTS) {
            byte[] bytes = readResource(snapshot.resource());
            assertNotNull(bytes, "classpath 缺少官方快照：" + snapshot.resource());
            assertEquals(snapshot.bytes(), bytes.length,
                    snapshot.resource() + " 字节数漂移，必须显式复核");
            assertEquals(snapshot.sha256(), sha256Hex(bytes),
                    snapshot.resource() + " SHA-256 漂移，必须显式复核");
            expected.addAll(parseOperations(snapshot, bytes));
        }

        assertEquals(64, expected.size(), "五份官方快照应包含 64 条 operation");
        assertEquals(expected.size(), SpApiOperationCatalog.operations().size(),
                "目录条数必须与官方快照一致");

        for (SpApiOperationSpec wanted : expected) {
            Optional<SpApiOperationSpec> found = SpApiOperationCatalog.find(wanted.operationId());
            assertTrue(found.isPresent(), "目录缺少 operation: " + wanted.operationId());
            assertEquals(wanted, found.orElseThrow(),
                    wanted.operationId() + " 的官方元数据不一致");
        }

        assertEquals(10, SpApiOperationCatalog.operationsFor("notifications").size());
        assertEquals(5, SpApiOperationCatalog.operationsFor("listingsItems").size());
        assertEquals(2, SpApiOperationCatalog.operationsFor("productPricing").size());
        assertEquals(2, SpApiOperationCatalog.operationsFor("catalogItems").size());
        assertEquals(45, SpApiOperationCatalog.operationsFor("fbaInbound").size());
        assertTrue(SpApiOperationCatalog.operationsFor("unknown").isEmpty());
        assertTrue(SpApiOperationCatalog.find(null).isEmpty());
        assertTrue(SpApiOperationCatalog.find("unknown.operation").isEmpty());
        assertFalse(SpApiOperationCatalog.find("notifications.sendTestNotification")
                .orElseThrow().grantless() == false);
    }

    private static List<SpApiOperationSpec> parseOperations(Snapshot snapshot, byte[] bytes) {
        JsonObject model = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject paths = model.getAsJsonObject("paths");
        assertNotNull(paths, snapshot.resource() + " 缺少 paths");

        List<SpApiOperationSpec> result = new ArrayList<>();
        for (String path : paths.keySet()) {
            JsonObject pathItem = paths.getAsJsonObject(path);
            for (String method : HTTP_METHODS) {
                if (!pathItem.has(method)) {
                    continue;
                }
                JsonObject operation = pathItem.getAsJsonObject(method);
                String rawOperationId = text(operation, "operationId");
                assertNotNull(rawOperationId, snapshot.resource() + " " + method + " " + path
                        + " 缺少 operationId");
                String operationId = snapshot.family() + "." + rawOperationId;
                result.add(new SpApiOperationSpec(
                        operationId,
                        snapshot.family(),
                        method.toUpperCase(),
                        path,
                        successStatuses(operation),
                        NOTIFICATIONS_GRANTLESS.contains(operationId),
                        requiredBody(operation, model),
                        requiredParameters(operation, "path"),
                        requiredParameters(operation, "query"),
                        requiredBodyFields(operation, model)));
            }
        }
        return result;
    }

    private static List<Integer> successStatuses(JsonObject operation) {
        JsonObject responses = operation.getAsJsonObject("responses");
        assertNotNull(responses, "operation 缺少 responses");
        List<Integer> statuses = new ArrayList<>();
        for (String status : responses.keySet()) {
            try {
                int value = Integer.parseInt(status);
                if (value >= 200 && value < 300) {
                    statuses.add(value);
                }
            } catch (NumberFormatException ignored) {
                // OpenAPI allows non-numeric response keys such as "default".
            }
        }
        statuses.sort(Integer::compareTo);
        return List.copyOf(statuses);
    }

    private static Set<String> requiredParameters(JsonObject operation, String location) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        JsonArray parameters = operation.getAsJsonArray("parameters");
        if (parameters == null) {
            return Set.of();
        }
        for (JsonElement element : parameters) {
            JsonObject parameter = element.getAsJsonObject();
            if (location.equals(text(parameter, "in")) && Boolean.TRUE.equals(bool(parameter, "required"))) {
                names.add(text(parameter, "name"));
            }
        }
        return Set.copyOf(names);
    }

    private static boolean requiredBody(JsonObject operation, JsonObject model) {
        BodySpec body = bodySpec(operation, model);
        return body != null && body.required();
    }

    private static Set<String> requiredBodyFields(JsonObject operation, JsonObject model) {
        BodySpec body = bodySpec(operation, model);
        if (body == null || body.schema() == null) {
            return Set.of();
        }
        JsonObject schema = resolveSchema(body.schema(), model);
        JsonArray required = schema == null ? null : schema.getAsJsonArray("required");
        if (required == null) {
            return Set.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonElement element : required) {
            names.add(element.getAsString());
        }
        return Set.copyOf(names);
    }

    private static BodySpec bodySpec(JsonObject operation, JsonObject model) {
        JsonArray parameters = operation.getAsJsonArray("parameters");
        if (parameters != null) {
            for (JsonElement element : parameters) {
                JsonObject parameter = element.getAsJsonObject();
                if ("body".equals(text(parameter, "in"))) {
                    return new BodySpec(Boolean.TRUE.equals(bool(parameter, "required")),
                            parameter.getAsJsonObject("schema"));
                }
            }
        }
        JsonObject requestBody = operation.getAsJsonObject("requestBody");
        if (requestBody == null) {
            return null;
        }
        JsonObject content = requestBody.getAsJsonObject("content");
        JsonObject media = content == null ? null : content.getAsJsonObject("application/json");
        return new BodySpec(Boolean.TRUE.equals(bool(requestBody, "required")),
                media == null ? null : media.getAsJsonObject("schema"));
    }

    private static JsonObject resolveSchema(JsonObject schema, JsonObject model) {
        if (schema == null || !schema.has("$ref") || !schema.get("$ref").isJsonPrimitive()) {
            return schema;
        }
        String ref = schema.get("$ref").getAsString();
        String name = ref.substring(ref.lastIndexOf('/') + 1);
        JsonObject definitions = model.getAsJsonObject("definitions");
        if (definitions != null && definitions.has(name)) {
            return definitions.getAsJsonObject(name);
        }
        JsonObject components = model.getAsJsonObject("components");
        JsonObject schemas = components == null ? null : components.getAsJsonObject("schemas");
        return schemas == null ? null : schemas.getAsJsonObject(name);
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? null : value.getAsString();
    }

    private static Boolean bool(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? null : value.getAsBoolean();
    }

    private static byte[] readResource(String resource) throws Exception {
        try (InputStream in = SpApiOperationCatalogContractTest.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(Character.forDigit((value >> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    private record BodySpec(boolean required, JsonObject schema) {
    }
}
