package com.amz.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * amz-service-spapi 返回的 FBA 费用预估（本地镜像 DTO）。
 * 字段名与 spapi 侧 {@code FeeEstimate} 一致，构成跨服务 JSON 契约。
 */
@Data
public class RemoteFeeEstimate {

    private String asin;
    private String sku;
    private BigDecimal price;
    private String currency;
    private BigDecimal referralFee;
    private BigDecimal fulfillmentFee;
    private BigDecimal otherFees;
    private BigDecimal totalFees;
    private BigDecimal estimatedNet;
}
