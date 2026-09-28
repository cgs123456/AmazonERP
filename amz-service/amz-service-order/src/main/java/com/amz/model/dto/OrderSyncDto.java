package com.amz.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 订单明细行（SP-API getOrderItems）。
     * 为空表示本次同步没有拿到行级数据（例如 orderItems 端点失败），
     * 不允许用空行或占位行冒充——明细缺失必须是可观测状态。
     */
    private List<OrderItemSyncDto> orderItems;
}
