package com.amz.client;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable metadata for one Amazon SP-API operation.
 *
 * <p>The catalog is deliberately declarative: transport, authentication, and
 * rate limiting remain the responsibility of the SP-API gateway.</p>
 */
public record SpApiOperationSpec(
        String operationId,
        String family,
        String method,
        String path,
        List<Integer> successStatuses,
        boolean grantless,
        boolean bodyRequired,
        Set<String> requiredPathParameters,
        Set<String> requiredQueryParameters,
        Set<String> requiredBodyFields) {

    public SpApiOperationSpec {
        operationId = requireText(operationId, "operationId");
        family = requireText(family, "family");
        method = requireText(method, "method").toUpperCase();
        path = requireText(path, "path");
        successStatuses = List.copyOf(Objects.requireNonNull(successStatuses, "successStatuses"));
        requiredPathParameters = Set.copyOf(
                Objects.requireNonNull(requiredPathParameters, "requiredPathParameters"));
        requiredQueryParameters = Set.copyOf(
                Objects.requireNonNull(requiredQueryParameters, "requiredQueryParameters"));
        requiredBodyFields = Set.copyOf(
                Objects.requireNonNull(requiredBodyFields, "requiredBodyFields"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
