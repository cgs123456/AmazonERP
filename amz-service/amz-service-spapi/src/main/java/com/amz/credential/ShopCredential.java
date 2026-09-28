package com.amz.credential;

import lombok.Data;

import java.time.LocalDateTime;

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

    /**
     * 乐观锁版本号；只参与 CAS 写入，不返回给管理接口。
     */
    private Long version;

    /**
     * 最近一次凭证更新时间；仅用于脱敏状态展示。
     */
    private LocalDateTime updateTime;
}
