package com.amz.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Amazon 订单同步 DTO（SP-API 拉取后的数据载体）。
 */
@Data
public class OrderSyncDto {
    private Long shopId;
    private String amazonOrderId;
    private String orderStatus;
    private LocalDateTime purchaseDate;
    private String fulfillmentChannel;
    private String shipServiceLevel;
    private String buyerName;
    private String marketplaceId;
    /** 订单总金额（SP-API OrderTotal.Amount） */
    private BigDecimal totalAmount;
    /** 币种（SP-API OrderTotal.CurrencyCode） */
    private String currency;
    /** Amazon 最后更新时间 */
    private LocalDateTime lastUpdateDate;
}
