package com.amz.client;

import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Executes a catalogued SP-API operation through the shared gateway contract.
 *
 * <p>Implementations must not construct HTTP requests, LWA tokens, SigV4
 * signatures, or rate-limit state themselves. This interface owns only
 * request-shape validation and path-template expansion so real and mock
 * implementations reject the same malformed calls.</p>
 */
public interface SpApiOperationClient {

    JsonObject execute(Long shopId,
                       String marketplaceId,
                       SpApiOperationSpec operation,
                       Map<String, String> pathParameters,
                       Map<String, String> queryParameters,
                       JsonObject body);

    static void validateRequest(SpApiOperationSpec operation,
                                Map<String, String> pathParameters,
                                Map<String, String> queryParameters,
                                JsonObject body) {
        SpApiOperationSpec known = SpApiOperationCatalog.find(
                        operation == null ? null : operation.operationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown SP-API operation: "
                                + (operation == null ? "null" : operation.operationId())));
        if (!known.equals(operation)) {
            throw new IllegalArgumentException(
                    "operation metadata does not match official catalog: " + operation.operationId());
        }
        if (known.successStatuses().isEmpty()) {
            throw new IllegalArgumentException(
                    "operation has no declared success status: " + operation.operationId());
        }
        requireParameters(known.requiredPathParameters(), pathParameters, "path");
        requireParameters(known.requiredQueryParameters(), queryParameters, "query");
        if (known.bodyRequired() && body == null) {
            throw new IllegalArgumentException(
                    "missing required body for operation: " + operation.operationId());
        }
        if (!known.requiredBodyFields().isEmpty()) {
            if (body == null) {
                throw new IllegalArgumentException(
                        "missing body containing required fields for operation: "
                                + operation.operationId());
            }
            for (String field : known.requiredBodyFields()) {
                if (!body.has(field) || body.get(field).isJsonNull()) {
                    throw new IllegalArgumentException(
                            "missing required body field '" + field + "' for operation: "
                                    + operation.operationId());
                }
            }
        }
    }

    static String resolvePath(SpApiOperationSpec operation, Map<String, String> pathParameters) {
        String path = operation.path();
        Map<String, String> values = pathParameters == null ? Map.of() : pathParameters;
        for (String name : operation.requiredPathParameters()) {
            String value = values.get(name);
            path = path.replace("{" + name + "}", encodePathSegment(value));
        }
        return path;
    }

    static String encodePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path parameter must not be blank");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static void requireParameters(Set<String> names,
                                          Map<String, String> parameters,
                                          String location) {
        Map<String, String> values = parameters == null ? Map.of() : parameters;
        for (String name : names) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "missing required " + location + " parameter: " + name);
            }
        }
    }
}
