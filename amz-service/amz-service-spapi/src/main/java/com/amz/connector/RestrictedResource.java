package com.amz.connector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One restricted resource requested from the SP-API Tokens API.
 *
 * <p>The Tokens API accepts a bounded, exact list of method/path pairs. This
 * value object normalizes and validates that input before any network call so
 * malformed resources fail locally and cannot accidentally widen a token.</p>
 */
public record RestrictedResource(String method, String path, List<String> dataElements) {

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "PUT", "POST", "DELETE");
    private static final int MAX_RESOURCES = 50;

    public RestrictedResource {
        method = normalizeMethod(method);
        path = normalizePath(path);
        dataElements = normalizeDataElements(dataElements);
    }

    /**
     * Stable cache key for an ordered set of restricted resources.
     *
     * <p>The returned value is a SHA-256 digest rather than a concatenation of
     * request paths. Paths can contain order identifiers, so they must not be
     * used as cache keys, metrics labels, or log values.</p>
     */
    public static String canonicalKey(List<RestrictedResource> resources) {
        List<RestrictedResource> normalized = validateResources(resources);
        StringBuilder canonical = new StringBuilder();
        for (RestrictedResource resource : normalized) {
            canonical.append(resource.method()).append('\n')
                    .append(resource.path()).append('\n')
                    .append(String.join("\u001f", resource.dataElements())).append('\u001e');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for restricted resource cache keys", e);
        }
    }

    private static String normalizeMethod(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(normalized)) {
            throw new IllegalArgumentException("unsupported method for restricted resource");
        }
        return normalized;
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (containsControlCharacter(value)) {
            throw new IllegalArgumentException("path must not contain control characters");
        }
        if (!value.startsWith("/") || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("path must be an absolute path without query or fragment");
        }
        return value;
    }

    private static List<String> normalizeDataElements(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("dataElements must not contain blank values");
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }

    private static List<RestrictedResource> validateResources(List<RestrictedResource> resources) {
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("restricted resources must not be empty");
        }
        if (resources.size() > MAX_RESOURCES) {
            throw new IllegalArgumentException("restricted resources must not exceed " + MAX_RESOURCES);
        }

        Set<RestrictedResource> unique = new LinkedHashSet<>();
        for (RestrictedResource resource : resources) {
            if (resource == null) {
                throw new IllegalArgumentException("restricted resources must not contain null entries");
            }
            if (!unique.add(resource)) {
                throw new IllegalArgumentException("restricted resources must not contain duplicates");
            }
        }

        List<RestrictedResource> sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparing(RestrictedResource::method)
                .thenComparing(RestrictedResource::path)
                .thenComparing(resource -> String.join("\u001f", resource.dataElements())));
        return List.copyOf(sorted);
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
