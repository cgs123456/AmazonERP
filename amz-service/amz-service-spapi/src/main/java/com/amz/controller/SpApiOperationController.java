package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.SpApiOperationCatalog;
import com.amz.client.SpApiOperationClient;
import com.amz.client.SpApiOperationSpec;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.context.UserContext;
import com.amz.result.ApiError;
import com.amz.result.Result;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Unified SP-API operation directory and execution boundary.
 *
 * <p>The controller is intentionally profile-neutral: the active profile selects
 * {@link SpApiOperationClient} (real or mock), while callers keep one stable API.
 * The mock implementation returns deterministic {@code synthetic=true} data and
 * never reaches Amazon; the real implementation uses the shared gateway,
 * credential store, rate limiter and SP-API request signing.</p>
 *
 * <p>{@code shopId} is carried in the JSON request body, so method-level
 * {@link ShopScoped} cannot inspect it. The controller therefore performs the
 * same strict, fail-closed shop check explicitly before invoking the client.</p>
 *
 * <p>Responses are recursively redacted for credential-like fields and string
 * values before leaving the service. This prevents a malformed request or a
 * diagnostic echo from placing LWA/AWS credentials in an HTTP response.</p>
 */
@RestController
@RequestMapping("/spapi/operations")
public class SpApiOperationController {

    private static final Logger log = LoggerFactory.getLogger(SpApiOperationController.class);

    private static final Gson GSON = new Gson();

    /** Credential-like JSON keys that must never be echoed to callers. */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(access[_-]?token|refresh[_-]?token|authorization|credential|signature"
                    + "|client[_-]?secret|secret[_-]?key|session[_-]?token|password"
                    + "|x-amz-(signature|credential|security-token))");

    private final SpApiOperationClient operationClient;

    public SpApiOperationController(SpApiOperationClient operationClient) {
        this.operationClient = operationClient;
    }

    /**
     * Returns the immutable official operation catalog without requiring a shop.
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping
    public Result<List<Map<String, Object>>> listOperations() {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (SpApiOperationSpec operation : SpApiOperationCatalog.operations()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("operationId", operation.operationId());
            item.put("family", operation.family());
            item.put("method", operation.method());
            item.put("path", operation.path());
            item.put("successStatuses", operation.successStatuses());
            item.put("grantless", operation.grantless());
            item.put("bodyRequired", operation.bodyRequired());
            item.put("requiredPathParameters", sorted(operation.requiredPathParameters()));
            item.put("requiredQueryParameters", sorted(operation.requiredQueryParameters()));
            item.put("requiredBodyFields", sorted(operation.requiredBodyFields()));
            operations.add(item);
        }
        return Result.success(List.copyOf(operations));
    }

    /**
     * Executes exactly one operation from the official catalog.
     *
     * @param operationId catalog operation id, for example {@code notifications.getDestinations}
     * @param request shop, marketplace, path/query parameters and optional JSON body
     * @return official response, or a structured failure with a stable error code
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/{operationId}")
    public Result<JsonObject> execute(@PathVariable String operationId,
                                      @RequestBody(required = false) OperationExecutionRequest request) {
        Optional<SpApiOperationSpec> operation = SpApiOperationCatalog.find(
                operationId == null ? null : operationId.trim());
        if (operation.isEmpty()) {
            return failure("unknown SP-API operation",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        if (request == null || request.getShopId() == null) {
            return failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        if (!UserContext.isShopAllowedStrict(request.getShopId())) {
            log.warn("SP-API operation shop-scope rejected: operation={}, shopId={}, userId={}",
                    operationId, request.getShopId(), UserContext.getUserId());
            return failure("no permission to operate on shopId=" + request.getShopId(),
                    ErrorSummary.localError(LocalApiException.CODE_FORBIDDEN));
        }

        Map<String, String> pathParameters = request.getPathParameters() == null
                ? Map.of() : request.getPathParameters();
        Map<String, String> queryParameters = request.getQueryParameters() == null
                ? Map.of() : request.getQueryParameters();

        try {
            JsonObject body = toJsonObject(request.getBody());
            JsonObject response = operationClient.execute(
                    request.getShopId(),
                    blankToNull(request.getMarketplaceId()),
                    operation.get(),
                    pathParameters,
                    queryParameters,
                    body);
            return Result.success(redact(response));
        } catch (IllegalArgumentException e) {
            return failure("invalid operation request: " + ErrorSummary.of(e),
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        } catch (Exception e) {
            ApiError error = ErrorSummary.toApiError(e);
            log.error("SP-API operation failed: operation={}, shopId={}",
                    operationId, request.getShopId(), e);
            String detail = "UPSTREAM_ERROR".equals(error.getCode())
                    ? "operation execution failed"
                    : ErrorSummary.of(e);
            return failure("operation execution failed: " + detail, error);
        }
    }

    private static List<String> sorted(Iterable<String> values) {
        List<String> sorted = new ArrayList<>();
        for (String value : values) {
            sorted.add(value);
        }
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static JsonObject toJsonObject(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        JsonElement parsed = JsonParser.parseString(GSON.toJson(body));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("body must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static JsonObject redact(JsonObject source) {
        if (source == null) {
            return null;
        }
        return (JsonObject) redactElement(source);
    }

    private static JsonElement redactElement(JsonElement source) {
        if (source == null || source.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (source.isJsonObject()) {
            JsonObject redacted = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : source.getAsJsonObject().entrySet()) {
                if (SENSITIVE_FIELD.matcher(entry.getKey()).find()) {
                    redacted.add(entry.getKey(), new JsonPrimitive("***"));
                } else {
                    redacted.add(entry.getKey(), redactElement(entry.getValue()));
                }
            }
            return redacted;
        }
        if (source.isJsonArray()) {
            JsonArray redacted = new JsonArray();
            for (JsonElement item : source.getAsJsonArray()) {
                redacted.add(redactElement(item));
            }
            return redacted;
        }
        if (source.isJsonPrimitive() && source.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(ErrorSummary.redact(source.getAsString()));
        }
        return source.deepCopy();
    }

    private static <T> Result<T> failure(String message, ApiError error) {
        return Result.failure(message, error == null
                ? ErrorSummary.localError(LocalApiException.CODE_INTERNAL_ERROR)
                : error);
    }

    /**
     * Request shape for one catalog operation.
     */
    public static class OperationExecutionRequest {
        private Long shopId;
        private String marketplaceId;
        private Map<String, String> pathParameters;
        private Map<String, String> queryParameters;
        private Map<String, Object> body;

        public Long getShopId() {
            return shopId;
        }

        public void setShopId(Long shopId) {
            this.shopId = shopId;
        }

        public String getMarketplaceId() {
            return marketplaceId;
        }

        public void setMarketplaceId(String marketplaceId) {
            this.marketplaceId = marketplaceId;
        }

        public Map<String, String> getPathParameters() {
            return pathParameters;
        }

        public void setPathParameters(Map<String, String> pathParameters) {
            this.pathParameters = pathParameters;
        }

        public Map<String, String> getQueryParameters() {
            return queryParameters;
        }

        public void setQueryParameters(Map<String, String> queryParameters) {
            this.queryParameters = queryParameters;
        }

        public Map<String, Object> getBody() {
            return body;
        }

        public void setBody(Map<String, Object> body) {
            this.body = body;
        }
    }
}
