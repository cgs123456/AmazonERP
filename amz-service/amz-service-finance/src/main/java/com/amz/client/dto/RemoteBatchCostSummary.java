package com.amz.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 采购批次成本聚合结果（amz-service-procurement 的跨服务 JSON 镜像）。
 *
 * <p>财务只读取聚合值，避免把 SKU 下全部库存批次行拉进内存。
 * 成本口径与采购侧 {@code BatchCostSummary} 保持逐字段一致：
 * {@code totalBatchCost} 优先取批次 {@code total_cost}，
 * 为空时才按 {@code unit_cost * quantity + freight_cost + customs_cost + other_cost} 计算。</p>
 */
@Data
public class RemoteBatchCostSummary {

    /** ACTIVE 批次数。 */
    private long batchCount;

    /** ACTIVE 批次总采购数量（不是剩余可用数量）。 */
    private long totalQuantity;

    /** ACTIVE 批次总成本。 */
    private BigDecimal totalBatchCost;
}
