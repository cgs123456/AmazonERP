package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Amazon 店铺实体（多店铺管理核心表）。
 * 存储 SP-API 凭证（refresh_token 等敏感信息需加密存储）。
 */
@Data
@TableName("amz_shop")
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

    @TableField("spapi_refresh_token")
    private String spapiRefreshToken;  // SP-API 刷新令牌（AES-256 加密存储）

    @TableField("spapi_client_id")
    private String spapiClientId;      // LWA Client ID

    @TableField("spapi_client_secret")
    private String spapiClientSecret;  // LWA Client Secret（加密存储）

    @TableField("status")
    private Integer status;            // 1=已授权 0=未授权 -1=授权过期

    @TableField("create_time")
    private LocalDateTime createTime;
}
