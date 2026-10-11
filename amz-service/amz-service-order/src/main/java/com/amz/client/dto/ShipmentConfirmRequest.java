package com.amz.client.dto;

import lombok.Data;

import java.util.List;

/**
 * 发货回传请求（order → spapi）。
 * <p>
 * 字段名与 spapi 侧 {@code ShipmentConfirmationController.ConfirmShipmentRequest} 对齐；
 * 两侧各自持有 DTO 而不是共享一个类，与仓库既有跨服务约定一致
 * （避免 order 依赖 spapi 的模块类）。
 */
@Data
public class ShipmentConfirmRequest {

    private Long shopId;

    private String marketplaceId;

    private String amazonOrderId;

    private String carrierCode;

    private String carrierName;

    private String trackingNumber;

    /** ISO-8601 带偏移；空则由 spapi 取当前 UTC 时间。 */
    private String shipDate;

    private List<OrderItemLine> orderItems;

    /**
     * 一行待确认明细。
     */
    @Data
    public static class OrderItemLine {
        private String orderItemId;
        private Integer quantity;
    }
}
