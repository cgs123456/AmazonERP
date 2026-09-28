package com.amz.credential;

import com.amz.auth.LwaTokenManager;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SP-API 凭证录入、轮换和删除服务。
 *
 * <p>敏感值只写不回显；部分更新留空即保留旧值；保存成功后必须同时失效
 * 新旧凭证对应的 LWA token。</p>
 */
@Service
public class ShopCredentialAdminService {

    private static final int MAX_CAS_ATTEMPTS = 3;

    private final ShopCredentialStore store;
    private final LwaTokenManager tokenManager;

    public ShopCredentialAdminService(ShopCredentialStore store, LwaTokenManager tokenManager) {
        this.store = store;
        this.tokenManager = tokenManager;
    }

    /**
     * 返回脱敏后的凭证配置状态。
     *
     * <p>刻意走 {@link ShopCredentialStore#describe(Long)} 而非 {@code getForAdmin}：
     * 状态面板只需要「有没有配」，不需要凭证明文，因此不应触发解密。
     * 这既避免把四个密钥的明文读进内存，也保证密文损坏或密钥轮换不匹配时
     * 管理员仍能看到记录（而不是收到 500）。</p>
     */
    public ShopCredentialStatus status(Long shopId) {
        requireShopId(shopId);
        ShopCredentialDescriptor descriptor = store.describe(shopId);
        if (descriptor == null) {
            return new ShopCredentialStatus(false, false, false, false, false,
                    null, null, null, null);
        }
        return toStatus(descriptor);
    }

    /**
     * 创建或部分更新凭证。
     */
    public ShopCredentialStatus upsert(Long shopId, ShopCredentialUpdateRequest request) {
        requireShopId(shopId);
        if (request == null) {
            throw new IllegalArgumentException("invalid shop credential fields: request");
        }

        validateAwsKeyUpdate(request);

        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            ShopCredential existing = store.getForAdmin(shopId);
            ShopCredential target = existing == null ? new ShopCredential() : copy(existing);
            target.setShopId(shopId);
            merge(target, request);

            List<String> problems = ShopCredentialValidator.validate(target);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid shop credential fields: " + String.join(",", problems));
            }

            target.setUpdateTime(LocalDateTime.now());
            Long expectedVersion = existing == null ? null
                    : existing.getVersion() == null ? 0L : existing.getVersion();
            try {
                store.putIfVersion(target, expectedVersion);
                tokenManager.invalidate(existing);
                tokenManager.invalidate(target);
                return toStatus(target);
            } catch (ShopCredentialConcurrentUpdateException conflict) {
                store.evictCache(shopId);
                if (attempt == MAX_CAS_ATTEMPTS - 1) {
                    throw conflict;
                }
            }
        }

        throw new IllegalStateException("shop credential CAS retry loop exhausted");
    }

    /**
     * 删除凭证并清除旧 token。
     *
     * <p>与 {@link #status(Long)} 同样走无明文读取：删除是「修复坏记录」的主要手段，
     * 绝不能因为密文解不出来而失败——那会让坏记录永远删不掉。
     * 代价是 token 失效退化为按 clientId 前缀驱逐（同一 LWA 应用下的兄弟店铺
     * token 也会一并失效，下次调用自动重新换取），这是安全方向的降级。</p>
     */
    public ShopCredentialStatus delete(Long shopId) {
        requireShopId(shopId);
        ShopCredentialDescriptor descriptor = store.describe(shopId);
        if (descriptor != null) {
            tokenManager.invalidate(descriptor.clientId());
        }
        store.remove(shopId);
        return new ShopCredentialStatus(false, false, false, false, false,
                null, null, null, null);
    }

    private static ShopCredentialStatus toStatus(ShopCredentialDescriptor descriptor) {
        boolean clientIdConfigured = hasText(descriptor.clientId());
        boolean clientSecretConfigured = descriptor.clientSecretPresent();
        boolean refreshTokenConfigured = descriptor.refreshTokenPresent();
        boolean awsKeysConfigured = descriptor.accessKeyPresent() && descriptor.secretKeyPresent();
        boolean routeConfigured = hasText(descriptor.marketplaceId())
                || hasText(descriptor.region());
        boolean configured = clientIdConfigured
                && clientSecretConfigured
                && refreshTokenConfigured
                && routeConfigured;
        return new ShopCredentialStatus(
                configured,
                clientIdConfigured,
                clientSecretConfigured,
                refreshTokenConfigured,
                awsKeysConfigured,
                descriptor.region(),
                descriptor.marketplaceId(),
                descriptor.sellerId(),
                descriptor.updateTime());
    }

    private static ShopCredentialStatus toStatus(ShopCredential credential) {
        boolean clientIdConfigured = hasText(credential.getClientId());
        boolean clientSecretConfigured = hasText(credential.getClientSecret());
        boolean refreshTokenConfigured = hasText(credential.getRefreshToken());
        boolean awsKeysConfigured = hasText(credential.getAccessKey())
                && hasText(credential.getSecretKey());
        boolean routeConfigured = hasText(credential.getMarketplaceId())
                || hasText(credential.getRegion());
        boolean configured = clientIdConfigured
                && clientSecretConfigured
                && refreshTokenConfigured
                && routeConfigured;
        return new ShopCredentialStatus(
                configured,
                clientIdConfigured,
                clientSecretConfigured,
                refreshTokenConfigured,
                awsKeysConfigured,
                credential.getRegion(),
                credential.getMarketplaceId(),
                credential.getSellerId(),
                credential.getUpdateTime());
    }

    private static void validateAwsKeyUpdate(ShopCredentialUpdateRequest request) {
        boolean accessKeyProvided = hasText(request.getAccessKey());
        boolean secretKeyProvided = hasText(request.getSecretKey());
        if (request.isClearAwsKeys() && (accessKeyProvided || secretKeyProvided)) {
            throw new IllegalArgumentException(
                    "invalid shop credential fields: clearAwsKeys cannot be combined with accessKey/secretKey");
        }
        if (!request.isClearAwsKeys() && accessKeyProvided != secretKeyProvided) {
            throw new IllegalArgumentException(
                    "invalid shop credential fields: accessKey/secretKey must be provided together");
        }
    }

    private static void merge(ShopCredential target, ShopCredentialUpdateRequest request) {
        if (hasText(request.getClientId())) {
            target.setClientId(request.getClientId());
        }
        if (hasText(request.getClientSecret())) {
            target.setClientSecret(request.getClientSecret());
        }
        if (hasText(request.getRefreshToken())) {
            target.setRefreshToken(request.getRefreshToken());
        }
        if (hasText(request.getRegion())) {
            target.setRegion(request.getRegion().trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (hasText(request.getMarketplaceId())) {
            target.setMarketplaceId(request.getMarketplaceId().trim());
        }
        if (hasText(request.getSellerId())) {
            target.setSellerId(request.getSellerId());
        }

        if (request.isClearAwsKeys()) {
            target.setAccessKey(null);
            target.setSecretKey(null);
        } else if (hasText(request.getAccessKey()) && hasText(request.getSecretKey())) {
            target.setAccessKey(request.getAccessKey());
            target.setSecretKey(request.getSecretKey());
        }
    }

    private static ShopCredential copy(ShopCredential source) {
        ShopCredential copy = new ShopCredential();
        copy.setShopId(source.getShopId());
        copy.setClientId(source.getClientId());
        copy.setClientSecret(source.getClientSecret());
        copy.setRefreshToken(source.getRefreshToken());
        copy.setAccessKey(source.getAccessKey());
        copy.setSecretKey(source.getSecretKey());
        copy.setRegion(source.getRegion());
        copy.setMarketplaceId(source.getMarketplaceId());
        copy.setSellerId(source.getSellerId());
        copy.setVersion(source.getVersion());
        copy.setUpdateTime(source.getUpdateTime());
        return copy;
    }

    private static void requireShopId(Long shopId) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId must not be null");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
