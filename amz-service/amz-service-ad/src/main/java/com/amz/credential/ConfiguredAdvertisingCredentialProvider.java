package com.amz.credential;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 Spring 配置的 Advertising API 凭证提供器。
 * <p>
 * 支持两种无代码切换方式：
 * <ul>
 *   <li>单店铺：advertising.shop-id + advertising.client-id/client-secret/refresh-token/profile-id</li>
 *   <li>多店铺：advertising.credentials.&lt;shopId&gt;.*</li>
 * </ul>
 * 未配置对应 shopId 时返回空，绝不把某一家店铺的凭证串给其他店铺。
 */
@Getter
@Setter
@Component
@Profile("!mock")
@ConfigurationProperties(prefix = "advertising")
public class ConfiguredAdvertisingCredentialProvider implements AdvertisingCredentialProvider {

    public static final String DEFAULT_API_ENDPOINT = "https://advertising-api.amazon.com";
    public static final String DEFAULT_TOKEN_ENDPOINT = "https://api.amazon.com/auth/o2/token";

    private String apiEndpoint = DEFAULT_API_ENDPOINT;
    private String tokenEndpoint = DEFAULT_TOKEN_ENDPOINT;
    private String shopId;
    private String clientId;
    private String clientSecret;
    private String refreshToken;
    private String profileId;
    private String endpoint;
    private Map<String, ShopCredentialProperties> credentials = new LinkedHashMap<>();

    @Override
    public Optional<AdvertisingCredential> get(Long requestedShopId) {
        if (requestedShopId == null) {
            return Optional.empty();
        }
        ShopCredentialProperties selected = credentials.get(String.valueOf(requestedShopId));
        if (selected == null && hasText(shopId) && String.valueOf(requestedShopId).equals(shopId.trim())) {
            selected = defaultShop();
        }
        if (selected == null) {
            return Optional.empty();
        }
        AdvertisingCredential credential = new AdvertisingCredential();
        credential.setShopId(requestedShopId);
        credential.setClientId(selected.getClientId());
        credential.setClientSecret(selected.getClientSecret());
        credential.setRefreshToken(selected.getRefreshToken());
        credential.setProfileId(selected.getProfileId());
        credential.setEndpoint(firstText(selected.getEndpoint(), endpoint, apiEndpoint, DEFAULT_API_ENDPOINT));
        credential.setTokenEndpoint(firstText(selected.getTokenEndpoint(), tokenEndpoint, DEFAULT_TOKEN_ENDPOINT));
        return credential.isComplete() ? Optional.of(credential) : Optional.empty();
    }

    @Override
    public Set<Long> configuredShopIds() {
        LinkedHashSet<Long> shopIds = new LinkedHashSet<>();
        addShopId(shopIds, shopId);
        if (credentials != null) {
            for (String configuredShopId : credentials.keySet()) {
                addShopId(shopIds, configuredShopId);
            }
        }
        LinkedHashSet<Long> usableShopIds = new LinkedHashSet<>();
        for (Long shopId : shopIds) {
            if (get(shopId).isPresent()) {
                usableShopIds.add(shopId);
            }
        }
        return Set.copyOf(usableShopIds);
    }

    private static void addShopId(Set<Long> shopIds, String value) {
        if (!hasText(value)) {
            return;
        }
        try {
            shopIds.add(Long.valueOf(value.trim()));
        } catch (NumberFormatException ignored) {
            // 非数字键不是合法店铺 ID，不能被当成可同步店铺。
        }
    }
    private ShopCredentialProperties defaultShop() {
        ShopCredentialProperties properties = new ShopCredentialProperties();
        properties.setClientId(clientId);
        properties.setClientSecret(clientSecret);
        properties.setRefreshToken(refreshToken);
        properties.setProfileId(profileId);
        properties.setEndpoint(endpoint);
        properties.setTokenEndpoint(tokenEndpoint);
        return properties;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Getter
    @Setter
    public static class ShopCredentialProperties {
        private String clientId;
        private String clientSecret;
        private String refreshToken;
        private String profileId;
        private String endpoint;
        private String tokenEndpoint;
    }
}
