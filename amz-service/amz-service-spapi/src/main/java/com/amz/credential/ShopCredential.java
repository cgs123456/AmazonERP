package com.amz.credential;

import lombok.Data;

/**
 * 店铺 SP-API 凭证 POJO。
 * 在内存中（ShopCredentialStore）缓存使用，敏感字段（refresh_token / secret_key）需在持久化时加密。
 */
@Data
public class ShopCredential {

    /**
     * 店铺主键 ID。
     */
    private Long shopId;

    /**
     * LWA Client ID。
     */
    private String clientId;

    /**
     * LWA Client Secret。
     */
    private String clientSecret;

    /**
     * SP-API 刷新令牌。
     */
    private String refreshToken;

    /**
     * AWS Access Key ID。
     */
    private String accessKey;

    /**
     * AWS Secret Access Key。
     */
    private String secretKey;

    /**
     * SP-API 区域：NA / EU / FE。
     */
    private String region;

    /**
     * Amazon Marketplace ID（如 ATVPDKIKX0DER 表示美国站）。
     */
    private String marketplaceId;

    /**
     * Amazon Seller ID。
     */
    private String sellerId;
}
