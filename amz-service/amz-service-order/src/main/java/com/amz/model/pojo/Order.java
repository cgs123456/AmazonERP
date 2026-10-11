package com.amz.model.pojo;

import com.amz.annotation.FieldPermission;
import com.amz.enums.ConfidentialLevel;
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
    private Long couponId;

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
    @FieldPermission(sensitiveLevel = ConfidentialLevel.CONFIDENTIAL)
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

    // ===== 发货回传（V6，FBM 确认发货）=====

    @TableField("carrier_code")
    private String carrierCode;             // 承运商代码（Amazon confirmShipment 必填）

    @TableField("carrier_name")
    private String carrierName;             // 承运商名称（carrierCode=Other 时必填）

    @TableField("ship_confirmed_at")
    private LocalDateTime shipConfirmedAt;  // 向 Amazon 回传发货的时间

    /**
     * 回传状态：NOT_CONFIRMED / CONFIRMED。
     * <p>
     * 与 {@code orderStatus}（Amazon 侧订单状态，由同步拉取写入）语义不同：
     * 本字段由本地回传动作推进。混用两者会让「Amazon 说已发货」与
     * 「我们已通知 Amazon 发货」无法区分。
     */
    @TableField("fulfillment_status")
    private String fulfillmentStatus;
}
