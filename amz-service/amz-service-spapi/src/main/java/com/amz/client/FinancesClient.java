package com.amz.client;

import com.amz.client.dto.FinancialEvent;

import java.util.List;

/**
 * SP-API Finances API（v0）客户端：按入账时间增量拉取财务事件。
 * <p>
 * 返回已按业务口径解析的四类事件（INCOME / REFUND / FEE / ADJUSTMENT），
 * 上层无需再处理 SP-API 的原始嵌套结构（ShipmentEventList / RefundEventList / AdjustmentEventList）。
 * <p>
 * 时间参数为 ISO 8601（UTC），如 2026-09-01T00:00:00Z；均可为 null（不限）。
 */
public interface FinancesClient {

    /**
     * 拉取指定入账时间窗口内的财务事件（含分页，自动翻页）。
     *
     * @param shopId       店铺 ID
     * @param postedAfter  入账时间下界（含），可为 null
     * @param postedBefore 入账时间上界（含），可为 null
     * @return 解析后的财务事件列表（有符号金额，直接求和即净影响）
     */
    List<FinancialEvent> listFinancialEvents(Long shopId, String postedAfter, String postedBefore);
}
