package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * SKU 在店铺内的 ACTIVE 批次成本聚合值。
 *
 * <p>字段与财务侧 {@code RemoteBatchCostSummary} 保持一一对应，
 * 数据库直接返回聚合结果，禁止调用方拉取批次明细后自行汇总。</p>
 */
@Data
public class BatchCostSummary {

    /** ACTIVE 批次数。 */
    private long batchCount;

    /** ACTIVE 批次总采购数量（不是剩余可用数量）。 */
    private long totalQuantity;

    /**
     * ACTIVE 批次总成本。
     * <p>优先取 {@code total_cost}；为空时才按
     * {@code unit_cost * quantity + freight_cost + customs_cost + other_cost} 计算。</p>
     */
    private BigDecimal totalBatchCost;
}
