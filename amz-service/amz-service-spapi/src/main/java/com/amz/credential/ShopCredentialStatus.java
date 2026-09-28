package com.amz.credential;

import java.time.LocalDateTime;

/**
 * 店铺 SP-API 凭证的安全状态视图。
 *
 * <p>只暴露配置状态与路由元数据，绝不包含 clientSecret、refreshToken、
 * accessKey 或 secretKey。</p>
 */
public record ShopCredentialStatus(
        boolean configured,
        boolean clientIdConfigured,
        boolean clientSecretConfigured,
        boolean refreshTokenConfigured,
        boolean awsKeysConfigured,
        String region,
        String marketplaceId,
        String sellerId,
        LocalDateTime updatedAt) {
}
