package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Amazon 订单明细行（对应 Flyway V5 表 {@code amz_order_item}）。
 * <p>
 * 为什么需要这张表：{@code amz_order} 只有一组单商品列（product_id / quantity / final_price），
 * 结构上装不下多商品订单；而 SP-API {@code getOrderItems} 返回的正是订单行列表，
 * 同库 {@code amz_profit_report} 也已按 (shop_id, amazon_order_id, sku) 建模。
 * 没有行式明细，按 SKU 的销量/利润/退货/FBA 费用就无法用 SQL 聚合。
 * <p>
 * 快照语义：asin / seller_sku / title 是**下单时冻结**的值。平台侧商品主数据会被卖家改动，
 * 若订单行随主数据漂移，历史订单就会和结算/利润对不上账。
 */
@Data
@TableName("amz_order_item")
public class OrderItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("shop_id")
    private Long shopId;

    @TableField("marketplace_id")
    private String marketplaceId;

    @TableField("amazon_order_id")
    private String amazonOrderId;

    /** Amazon OrderItemId：平台侧明细唯一标识 */
    @TableField("amazon_order_item_id")
    private String amazonOrderItemId;

    @TableField("asin")
    private String asin;

    @TableField("seller_sku")
    private String sellerSku;

    /** 本地商品 ID（对齐 amz_product.amz_product.id BIGINT）；无法匹配时保留 NULL，不允许伪造 */
    @TableField("product_id")
    private Long productId;

    @TableField("title")
    private String title;

    @TableField("quantity")
    private Integer quantity;

    @TableField("item_price")
    private BigDecimal itemPrice;

    @TableField("item_tax")
    private BigDecimal itemTax;

    @TableField("promotion_discount")
    private BigDecimal promotionDiscount;

    @TableField("currency")
    private String currency;

    @TableField("fulfillment_channel")
    private String fulfillmentChannel;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
