package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 店铺 SP-API 凭证持久化实体。
 * <p>
 * 对应 amz_spapi.amz_shop_credential 表，存储各店铺的 SP-API 凭证。
 * 敏感字段（client_secret / refresh_token / access_key / secret_key）
 * 均以 AES-256-GCM 密文形式存储，列名以 _encrypted 结尾以示区分。
 * 主键 shop_id 由业务层提供（IdType.INPUT），不自增。
 */
@Data
@TableName("amz_shop_credential")
public class ShopCredentialEntity {

    /** 店铺主键 ID（业务提供，非自增）。 */
    @TableId(value = "shop_id", type = IdType.INPUT)
    private Long shopId;

    /** LWA Client ID（非敏感，明文存储）。 */
    @TableField("client_id")
    private String clientId;

    /** LWA Client Secret（AES-256-GCM 密文）。 */
    @TableField("client_secret_encrypted")
    private String clientSecretEncrypted;

    /** SP-API 刷新令牌（AES-256-GCM 密文）。 */
    @TableField("refresh_token_encrypted")
    private String refreshTokenEncrypted;

    /** AWS Access Key ID（AES-256-GCM 密文）。 */
    @TableField("access_key_encrypted")
    private String accessKeyEncrypted;

    /** AWS Secret Access Key（AES-256-GCM 密文）。 */
    @TableField("secret_key_encrypted")
    private String secretKeyEncrypted;

    /** SP-API 区域：NA / EU / FE。 */
    @TableField("region")
    private String region;

    /** Amazon Marketplace ID（如 ATVPDKIKX0DER 表示美国站）。 */
    @TableField("marketplace_id")
    private String marketplaceId;

    /** Amazon Seller ID。 */
    @TableField("seller_id")
    private String sellerId;

    /** 创建时间。 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @TableField("update_time")
    private LocalDateTime updateTime;
}