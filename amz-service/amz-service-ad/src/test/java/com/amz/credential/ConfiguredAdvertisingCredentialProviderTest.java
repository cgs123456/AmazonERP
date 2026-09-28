package com.amz.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredAdvertisingCredentialProviderTest {

    @Test
    @DisplayName("凭证提供器应枚举已配置店铺，供空库调度发现店铺")
    void exposesConfiguredShopIds() {
        ConfiguredAdvertisingCredentialProvider provider = new ConfiguredAdvertisingCredentialProvider();
        provider.setShopId("1001");
        provider.setClientId("client-1001");
        provider.setClientSecret("secret-1001");
        provider.setRefreshToken("refresh-1001");
        provider.setProfileId("profile-1001");

        Map<String, ConfiguredAdvertisingCredentialProvider.ShopCredentialProperties> credentials = new LinkedHashMap<>();
        credentials.put("1002", completeShop("client-1002", "secret-1002", "refresh-1002", "profile-1002"));
        credentials.put("not-a-shop", completeShop("client-x", "secret-x", "refresh-x", "profile-x"));
        provider.setCredentials(credentials);

        assertEquals(Set.of(1001L, 1002L), provider.configuredShopIds());
    }

    @Test
    @DisplayName("单店铺属性完整时可直接构造可用凭证并使用官方默认端点")
    void bindsCompleteSingleShopProperties() {
        ConfiguredAdvertisingCredentialProvider provider = bind(Map.of(
                "advertising.shop-id", "1001",
                "advertising.client-id", "client-1001",
                "advertising.client-secret", "secret-1001",
                "advertising.refresh-token", "refresh-1001",
                "advertising.profile-id", "profile-1001"));

        AdvertisingCredential credential = provider.get(1001L).orElseThrow();

        assertEquals(1001L, credential.getShopId());
        assertEquals("client-1001", credential.getClientId());
        assertEquals("secret-1001", credential.getClientSecret());
        assertEquals("refresh-1001", credential.getRefreshToken());
        assertEquals("profile-1001", credential.getProfileId());
        assertEquals(ConfiguredAdvertisingCredentialProvider.DEFAULT_API_ENDPOINT, credential.getEndpoint());
        assertEquals(ConfiguredAdvertisingCredentialProvider.DEFAULT_TOKEN_ENDPOINT, credential.getTokenEndpoint());
        assertTrue(credential.isComplete());
        assertEquals(Set.of(1001L), provider.configuredShopIds());
    }

    @Test
    @DisplayName("广告专用环境变量必须显式映射到 advertising 配置")
    void applicationYamlMapsDedicatedAdvertisingEnvironmentVariables() throws IOException {
        Map<String, Object> advertising = advertisingConfig();

        assertEquals("${AD_API_ENDPOINT:https://advertising-api.amazon.com}", advertising.get("api-endpoint"));
        assertEquals("${AD_TOKEN_ENDPOINT:https://api.amazon.com/auth/o2/token}", advertising.get("token-endpoint"));
        assertEquals("${AD_SHOP_ID:}", advertising.get("shop-id"));
        assertEquals("${AD_CLIENT_ID:}", advertising.get("client-id"));
        assertEquals("${AD_CLIENT_SECRET:}", advertising.get("client-secret"));
        assertEquals("${AD_REFRESH_TOKEN:}", advertising.get("refresh-token"));
        assertEquals("${AD_PROFILE_ID:}", advertising.get("profile-id"));
    }

    @Test
    @DisplayName("单店铺任一必需字段缺失时不得解析或枚举该店铺")
    void incompleteSingleShopCredentialIsNotUsable() {
        ConfiguredAdvertisingCredentialProvider provider = new ConfiguredAdvertisingCredentialProvider();
        provider.setShopId("1001");
        provider.setClientId("client-1001");
        provider.setClientSecret("secret-1001");
        provider.setRefreshToken("refresh-1001");
        // profile-id 缺失

        assertTrue(provider.get(1001L).isEmpty());
        assertTrue(provider.configuredShopIds().isEmpty());
    }

    @Test
    @DisplayName("生产与演示部署模板必须完整传递广告专用环境变量")
    void deploymentTemplatesExposeAllAdvertisingVariables() throws IOException {
        Path root = findRepoRoot();
        Set<String> nonSecret = Set.of(
                "AD_API_ENDPOINT", "AD_TOKEN_ENDPOINT", "AD_SHOP_ID", "AD_PROFILE_ID");
        Set<String> secret = Set.of("AD_CLIENT_ID", "AD_CLIENT_SECRET", "AD_REFRESH_TOKEN");

        String productionEnv = Files.readString(root.resolve(".env.example"), StandardCharsets.UTF_8);
        String demoEnv = Files.readString(root.resolve(".env.demo.example"), StandardCharsets.UTF_8);
        String compose = Files.readString(root.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
        String configMap = Files.readString(root.resolve("k8s/configmap.yaml"), StandardCharsets.UTF_8);
        String secretManifest = Files.readString(root.resolve("k8s/secret.yaml"), StandardCharsets.UTF_8);
        String adService = Files.readString(root.resolve("k8s/services/amz-service-ad.yaml"), StandardCharsets.UTF_8);

        for (String key : nonSecret) {
            assertTrue(productionEnv.contains(key + "="), ".env.example 缺少 " + key);
            assertTrue(demoEnv.contains(key + "="), ".env.demo.example 缺少 " + key);
            assertTrue(compose.contains(key + "="), "docker-compose.yml 缺少 " + key);
            assertTrue(configMap.contains(key + ":"), "k8s/configmap.yaml 缺少 " + key);
            assertTrue(adService.contains("name: " + key), "amz-service-ad.yaml 缺少 " + key);
        }
        for (String key : secret) {
            assertTrue(productionEnv.contains(key + "="), ".env.example 缺少 " + key);
            assertTrue(demoEnv.contains(key + "="), ".env.demo.example 缺少 " + key);
            assertTrue(compose.contains(key + "="), "docker-compose.yml 缺少 " + key);
            assertTrue(secretManifest.contains(key + ":"), "k8s/secret.yaml 缺少 " + key);
            assertTrue(adService.contains("name: " + key), "amz-service-ad.yaml 缺少 " + key);
        }
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("k8s/services"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }
    private static ConfiguredAdvertisingCredentialProvider bind(Map<String, Object> properties) {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach((key, value) -> environment.withProperty(key, String.valueOf(value)));
        return Binder.get(environment)
                .bind("advertising", Bindable.of(ConfiguredAdvertisingCredentialProvider.class))
                .get();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> advertisingConfig() throws IOException {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource("application.yml").getInputStream(), StandardCharsets.UTF_8)) {
            Map<String, Object> root = new Yaml().load(reader);
            return (Map<String, Object>) root.get("advertising");
        }
    }

    private static ConfiguredAdvertisingCredentialProvider.ShopCredentialProperties completeShop(
            String clientId, String clientSecret, String refreshToken, String profileId) {
        ConfiguredAdvertisingCredentialProvider.ShopCredentialProperties properties =
                new ConfiguredAdvertisingCredentialProvider.ShopCredentialProperties();
        properties.setClientId(clientId);
        properties.setClientSecret(clientSecret);
        properties.setRefreshToken(refreshToken);
        properties.setProfileId(profileId);
        return properties;
    }
}