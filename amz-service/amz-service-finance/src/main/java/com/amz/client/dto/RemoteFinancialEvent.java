package com.amz.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * amz-service-spapi 返回的财务事件（本地镜像 DTO）。
 * <p>
 * 字段名与 spapi 模块 {@code com.amz.client.dto.FinancialEvent} 完全一致，
 * 为跨服务 JSON 反序列化契约 —— 改任一侧字段名都会破坏该契约。
 */
@Data
public class RemoteFinancialEvent {

    /** 幂等键：type:orderId:postedAt:feeType:amount。 */
    private String eventId;
    /** INCOME / REFUND / FEE / ADJUSTMENT。 */
    private String type;
    private String amazonOrderId;
    private String sku;
    private String asin;
    private String postedAt;
    /** 有符号金额（保留平台原始方向）。 */
    private BigDecimal amount;
    private String currency;
    private String feeType;
    private String description;
}
