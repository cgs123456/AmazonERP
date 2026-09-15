package com.amz.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 采购批次成本（amz-service-procurement 的 InventoryBatch 本地镜像）。
 * <p>
 * 字段名必须与 procurement 侧 {@code com.amz.model.InventoryBatch} 保持一致 ——
 * 这是跨服务 JSON 契约。
 * <p>
 * 注意 {@code freightCost}：批次里已经含头程运费，
 * 因此单品利润<b>不再单独加一遍物流模块的头程分摊</b>，否则同一笔运费计两次。
 */
@Data
public class RemoteInventoryBatch {

    private String batchNo;
    private String sku;
    private Integer quantity;
    private Integer availableQuantity;
    /** 采购单价。 */
    private BigDecimal unitCost;
    /** 该批次头程运费（已含在总成本内）。 */
    private BigDecimal freightCost;
    /** 关税（已含在总成本内）。 */
    private BigDecimal customsCost;
    private BigDecimal otherCost;
    /** 批次总成本 = 采购 + 运费 + 关税 + 其他。 */
    private BigDecimal totalCost;
    private String status;
}
