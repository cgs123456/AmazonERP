package com.amz.connector;

import com.amz.client.TokensClient;
import com.amz.config.RestrictedDataProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * In-memory cache and single-flight refresh coordinator for restricted data
 * tokens.
 *
 * <p>Tokens are scoped by shop, marketplace, and canonical resource set. The
 * manager never persists tokens and never includes resource paths or token
 * values in diagnostics. When restricted data is disabled, requests fail
 * closed before any Tokens API call is made.</p>
 */
@Component
@Profile("!mock")
public class RestrictedDataTokenManager {

    public static final String CODE_RESTRICTED_DATA_DISABLED = "RESTRICTED_DATA_DISABLED";
    private static final String CODE_INVALID_RESTRICTED_TOKEN = "INVALID_RESTRICTED_TOKEN";

    private final TokensClient tokensClient;
    private final RestrictedDataProperties properties;
    private final Clock clock;
    private final Object cacheLock = new Object();
    private final Map<CacheKey, CacheEntry> cache;
    private final ConcurrentMap<CacheKey, Inflight> inflight = new ConcurrentHashMap<>();

    /**
     * Production constructor selected by the container (see P0-65 in TokensClient).
     * Two constructors with no {@code @Autowired} make Spring look for a no-arg
     * constructor that does not exist, failing the context at startup.
     */
    @Autowired
    public RestrictedDataTokenManager(TokensClient tokensClient,
                                      RestrictedDataProperties properties) {
        this(tokensClient, properties, Clock.systemUTC());
    }

    /**
     * Test/explicit-assembly constructor with a deterministic clock.
     */
    public RestrictedDataTokenManager(TokensClient tokensClient,
                                      RestrictedDataProperties properties,
                                      Clock clock) {
        this.tokensClient = Objects.requireNonNull(tokensClient, "tokensClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        properties.validate();
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
                return size() > properties.getMaxCacheEntries();
            }
        };
    }

    /**
     * Returns a valid RDT for the exact resource scope.
     *
     * @throws LocalApiException when restricted data is disabled
     * @throws IllegalArgumentException for invalid scope or resources
     */
    public String getToken(Long shopId, String marketplaceId,
                           java.util.List<RestrictedResource> resources) {
        if (!properties.isEnabled()) {
            throw LocalApiException.of(
                    CODE_RESTRICTED_DATA_DISABLED,
                    "Restricted data requests are disabled");
        }
        if (shopId == null || shopId <= 0L) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        if (marketplaceId == null || marketplaceId.isBlank()) {
            throw new IllegalArgumentException("marketplaceId must not be blank");
        }

        String resourceKey = RestrictedResource.canonicalKey(resources);
        CacheKey key = new CacheKey(shopId, marketplaceId.trim(), resourceKey);
        Instant now = clock.instant();

        Inflight created;
        Inflight existing;
        synchronized (cacheLock) {
            CacheEntry cached = cache.get(key);
            if (cached != null) {
                if (isUsable(cached, now)) {
                    return cached.token();
                }
                cache.remove(key);
            }

            existing = inflight.get(key);
            if (existing == null) {
                created = new Inflight();
                inflight.put(key, created);
            } else {
                created = null;
            }
        }

        if (existing != null) {
            return await(existing.future);
        }
        return refresh(key, shopId, marketplaceId, resources, created);
    }

    private String refresh(CacheKey key, Long shopId, String marketplaceId,
                           java.util.List<RestrictedResource> resources, Inflight flight) {
        try {
            TokensClient.RestrictedDataToken token =
                    tokensClient.requestToken(shopId, marketplaceId, resources);
            if (token == null || token.token() == null || token.token().isBlank()
                    || token.expiresAt() == null || !token.expiresAt().isAfter(clock.instant())) {
                throw LocalApiException.of(
                        CODE_INVALID_RESTRICTED_TOKEN,
                        "Tokens API returned an unusable restricted data token");
            }

            synchronized (cacheLock) {
                if (!flight.cancelled && inflight.get(key) == flight) {
                    cache.put(key, new CacheEntry(token.token(), token.expiresAt()));
                }
                inflight.remove(key, flight);
            }
            flight.future.complete(token.token());
            return token.token();
        } catch (RuntimeException error) {
            synchronized (cacheLock) {
                inflight.remove(key, flight);
            }
            flight.future.completeExceptionally(error);
            throw error;
        }
    }

    /**
     * Removes only the cache entry matching the supplied scope.
     */
    public void invalidate(Long shopId, String marketplaceId,
                           java.util.List<RestrictedResource> resources) {
        if (shopId == null || marketplaceId == null || marketplaceId.isBlank()) {
            return;
        }
        String resourceKey = RestrictedResource.canonicalKey(resources);
        CacheKey key = new CacheKey(shopId, marketplaceId.trim(), resourceKey);
        Inflight removed;
        synchronized (cacheLock) {
            cache.remove(key);
            removed = inflight.remove(key);
            if (removed != null) {
                removed.cancelled = true;
            }
        }
    }

    /**
     * Clears all in-memory entries. Intended for explicit administrative use.
     */
    public void clear() {
        synchronized (cacheLock) {
            cache.clear();
            inflight.values().forEach(value -> value.cancelled = true);
            inflight.clear();
        }
    }

    private boolean isUsable(CacheEntry entry, Instant now) {
        return entry.expiresAt().isAfter(now.plusSeconds(properties.getRefreshSkewSeconds()));
    }

    private static String await(CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw error;
        }
    }

    @Override
    public String toString() {
        int cacheSize;
        synchronized (cacheLock) {
            cacheSize = cache.size();
        }
        return "RestrictedDataTokenManager[cacheSize=" + cacheSize
                + ", inflight=" + inflight.size() + "]";
    }

    private record CacheKey(long shopId, String marketplaceId, String resourceKey) {
    }

    private record CacheEntry(String token, Instant expiresAt) {
    }

    private static final class Inflight {
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private volatile boolean cancelled;
    }
}
