package com.amz.model.pojo;

import com.amz.config.CryptoTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Amazon 店铺实体（多店铺管理核心表）。
 * 存储 SP-API 凭证，spapi_refresh_token / spapi_client_secret 通过
 * {@link CryptoTypeHandler} 在持久化时自动 AES-256-GCM 加密、读取时自动解密。
 */
@Data
@TableName(value = "amz_shop", autoResultMap = true)
public class Shop {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("shop_name")
    private String shopName;           // 店铺名称

    @TableField("marketplace_id")
    private String marketplaceId;      // Amazon Marketplace ID（ATVPDKIKX0DER=美国站）

    @TableField("region")
    private String region;             // NA/EU/FE（北美/欧洲/远东）

    @TableField("seller_id")
    private String sellerId;           // Amazon Seller ID

    @TableField(value = "spapi_refresh_token", typeHandler = CryptoTypeHandler.class)
    private String spapiRefreshToken;  // SP-API 刷新令牌（AES-256-GCM 加密存储）

    @TableField("spapi_client_id")
    private String spapiClientId;      // LWA Client ID

    @TableField(value = "spapi_client_secret", typeHandler = CryptoTypeHandler.class)
    private String spapiClientSecret;  // LWA Client Secret（AES-256-GCM 加密存储）

    @TableField("status")
    private Integer status;            // 1=已授权 0=未授权 -1=授权过期

    @TableField("create_time")
    private LocalDateTime createTime;
}
