package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体（增强：多店铺 + Amazon 订单同步字段 + 状态机）
 */
@Data
@TableName("amz_order")
public class Order {

    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 产品id
     */
    @TableField("product_id")
    private Integer productId;

    /**
     * 商品数量
     */
    private Integer quantity;

    /**
     * 优惠券id
     */
    @TableField("coupon_id")
    private Integer couponId;

    /**
     * 最终价格
     */
    @TableField("final_price")
    private BigDecimal finalPrice;

    /**
     * 订单归属人ID
     */
    @TableField("user_id")
    private Integer userId;

    /**
     * 状态（原状态字段保留）
     */
    private Integer status;

    // ===== Amazon ERP 新增字段 =====

    @TableField("shop_id")
    private Long shopId;                    // 所属店铺

    @TableField("amazon_order_id")
    private String amazonOrderId;           // Amazon 订单号（如 114-0000000-7599439）

    @TableField("marketplace_id")
    private String marketplaceId;           // 站点 ID

    @TableField("order_status")
    private String orderStatus;             // Amazon 订单状态：Pending/Unshipped/Shipped/Canceled

    @TableField("buyer_name")
    private String buyerName;               // 买家姓名（PII，需权限）

    @TableField("purchase_date")
    private LocalDateTime purchaseDate;     // 购买时间（Amazon 时间）

    @TableField("last_update_date")
    private LocalDateTime lastUpdateDate;   // 最后更新时间

    @TableField("fulfillment_channel")
    private String fulfillmentChannel;      // AFN（FBA）或 MFN（自发货）

    @TableField("ship_service_level")
    private String shipServiceLevel;        // Standard/Expedited/Priority

    @TableField("tracking_number")
    private String trackingNumber;          // 物流跟踪号

    @TableField("sync_status")
    private Integer syncStatus;             // 0=未同步 1=已同步本地 2=已上传跟踪号 3=已完成
}
