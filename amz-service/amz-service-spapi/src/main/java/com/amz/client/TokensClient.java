package com.amz.client;

import com.amz.connector.RestrictedResource;
import com.amz.connector.SpApiCallException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Official SP-API Tokens API client.
 *
 * <p>The Tokens API is authenticated with the normal LWA access token and
 * returns a short-lived restricted data token (RDT). The RDT is intentionally
 * returned as an immutable value and is never logged, persisted, or included
 * in diagnostics.</p>
 */
@Component
@Profile("!mock")
public class TokensClient {

    public static final String OPERATION_CREATE_RESTRICTED_DATA_TOKEN =
            "tokens.createRestrictedDataToken";
    public static final String PATH_RESTRICTED_DATA_TOKEN =
            "/tokens/2021-03-01/restrictedDataToken";

    private static final String INVALID_RESPONSE = "INVALID_RESPONSE";

    private final SpApiGateway gateway;
    private final Clock clock;

    /**
     * Production constructor selected by the container.
     *
     * <p>Two constructors with no {@code @Autowired} make Spring fall back to a
     * no-arg constructor that does not exist here, which fails the context at
     * startup with {@code NoSuchMethodException: TokensClient.<init>()} (P0-65).
     * The deterministic-clock overload stays for tests and explicit assembly.
     */
    @Autowired
    public TokensClient(SpApiGateway gateway) {
        this(gateway, Clock.systemUTC());
    }

    /**
     * Test/explicit-assembly constructor with a deterministic clock.
     */
    public TokensClient(SpApiGateway gateway, Clock clock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Requests an RDT for an exact, bounded set of method/path resources.
     *
     * @throws IllegalArgumentException if the resource set is empty, duplicated,
     *                                  or larger than the official limit
     * @throws SpApiCallException       if the gateway or official response fails
     */
    public RestrictedDataToken requestToken(Long shopId, String marketplaceId,
                                            List<RestrictedResource> resources) {
        // Validate and canonicalize locally before resolving credentials or
        // reaching the network. This prevents a malformed request from being
        // broadened by a partial resource list.
        RestrictedResource.canonicalKey(resources);
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        String body = requestBody(resources);
        JsonObject response = gateway.callJsonWithStatusUsingLwa(
                "POST", shop, OPERATION_CREATE_RESTRICTED_DATA_TOKEN,
                PATH_RESTRICTED_DATA_TOKEN, null, body, 200);

        String token = requiredToken(response);
        long expiresIn = requiredPositiveExpiresIn(response);
        final Instant expiresAt;
        try {
            expiresAt = clock.instant().plusSeconds(expiresIn);
        } catch (DateTimeException | ArithmeticException e) {
            throw invalidResponse("expiresIn");
        }
        return new RestrictedDataToken(token, expiresAt);
    }

    private static String requestBody(List<RestrictedResource> resources) {
        JsonArray restrictedResources = new JsonArray();
        for (RestrictedResource resource : resources) {
            JsonObject item = new JsonObject();
            item.addProperty("method", resource.method());
            item.addProperty("path", resource.path());
            if (!resource.dataElements().isEmpty()) {
                JsonArray dataElements = new JsonArray();
                resource.dataElements().forEach(dataElements::add);
                item.add("dataElements", dataElements);
            }
            restrictedResources.add(item);
        }
        JsonObject root = new JsonObject();
        root.add("restrictedResources", restrictedResources);
        return new Gson().toJson(root);
    }

    private static String requiredToken(JsonObject response) {
        if (response == null) {
            throw invalidResponse("restrictedDataToken");
        }
        JsonElement element = response.get("restrictedDataToken");
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw invalidResponse("restrictedDataToken");
        }
        return element.getAsString();
    }

    private static long requiredPositiveExpiresIn(JsonObject response) {
        JsonElement element = response.get("expiresIn");
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw invalidResponse("expiresIn");
        }
        final long expiresIn;
        try {
            BigDecimal value = element.getAsBigDecimal();
            expiresIn = value.longValueExact();
        } catch (RuntimeException e) {
            throw invalidResponse("expiresIn");
        }
        if (expiresIn <= 0L) {
            throw invalidResponse("expiresIn");
        }
        return expiresIn;
    }

    private static SpApiCallException invalidResponse(String field) {
        return SpApiCallException.localFailure(
                OPERATION_CREATE_RESTRICTED_DATA_TOKEN,
                PATH_RESTRICTED_DATA_TOKEN,
                INVALID_RESPONSE,
                "Tokens API response missing or invalid " + field);
    }

    /**
     * In-memory RDT value. Its {@link #toString()} never contains the token.
     */
    public record RestrictedDataToken(String token, Instant expiresAt) {

        public RestrictedDataToken {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("restrictedDataToken must not be blank");
            }
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }

        @Override
        public String toString() {
            return "RestrictedDataToken[token=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }
}
