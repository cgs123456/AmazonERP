package com.amz.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * SP-API 订单明细行同步 DTO（{@code getOrderItems} 单行的数据载体）。
 * <p>
 * 金额一律用 {@link BigDecimal}（字符串解析），禁止 double 中转：
 * 财务列走 double 会在分位上产生不可对账的误差。
 */
@Data
public class OrderItemSyncDto {

    /** Amazon OrderItemId */
    private String amazonOrderItemId;
    private String asin;
    private String sellerSku;
    private String title;
    private Integer quantity;
    /** 行金额（原币种，含税） */
    private BigDecimal itemPrice;
    private BigDecimal itemTax;
    /** 促销折扣（原币种，正数） */
    private BigDecimal promotionDiscount;
    private String currency;
    private String fulfillmentChannel;
}
