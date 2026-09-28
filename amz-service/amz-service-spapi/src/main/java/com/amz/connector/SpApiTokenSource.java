package com.amz.connector;

import java.util.Locale;

/**
 * Authentication source persisted with every SP-API outbox call.
 *
 * <p>The source is part of the replay contract. A failed restricted-data call
 * must be replayed with a newly issued RDT; it must never silently fall back to
 * a normal LWA token.</p>
 */
public enum SpApiTokenSource {
    LWA,
    LWA_GRANTLESS,
    RDT;

    /**
     * Parses a persisted value. Missing values are compatible with pre-RDT
     * rows and therefore default to {@link #LWA}; unknown values fail closed.
     */
    public static SpApiTokenSource fromPersisted(String value) {
        if (value == null || value.isBlank()) {
            return LWA;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported SP-API token source: " + value);
        }
    }
}
