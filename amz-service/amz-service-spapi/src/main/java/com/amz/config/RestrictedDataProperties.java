package com.amz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SP-API restricted-data configuration.
 *
 * <p>Restricted data is disabled by default. Enabling it only makes the
 * technical RDT path available; it does not grant Amazon permissions or
 * establish that the seller application has been approved for PII access.</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "spapi.restricted-data")
public class RestrictedDataProperties {

    /** Whether restricted-data requests may be attempted. */
    private boolean enabled = false;

    /** Refresh an RDT this many seconds before its absolute expiry. */
    private long refreshSkewSeconds = 60L;

    /** Maximum number of in-memory RDT entries. */
    private int maxCacheEntries = 1000;

    /**
     * Validates the fail-safe configuration before a token request can occur.
     *
     * @throws IllegalArgumentException when the configuration is unusable
     */
    public void validate() {
        if (refreshSkewSeconds <= 0L) {
            throw new IllegalArgumentException("spapi.restricted-data.refresh-skew-seconds must be positive");
        }
        if (maxCacheEntries <= 0 || maxCacheEntries > 100_000) {
            throw new IllegalArgumentException("spapi.restricted-data.max-cache-entries must be between 1 and 100000");
        }
    }
}
