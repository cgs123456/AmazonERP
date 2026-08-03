package com.amz.service;

import com.amz.model.CostAllocation;
import com.amz.model.ProfitSnapshot;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 实时利润核算服务。
 */
public interface RealtimeProfitService {

    // ==================== 利润快照 ====================
    ProfitSnapshot snapshotProfit(Long shopId, String sku, String asin);
    List<ProfitSnapshot> listSnapshots(Long shopId, String sku, String startTime, String endTime);
    Map<String, Object> profitTrend(Long shopId, String sku, String asin, Integer hours);
    Map<String, Object> profitSummary(Long shopId, String startTime, String endTime);

    // ==================== 费用分摊 ====================
    CostAllocation saveAllocation(CostAllocation allocation);
    List<CostAllocation> listAllocations(Long shopId, String costType, String startDate, String endDate);
    Map<String, BigDecimal> allocateCost(Long shopId, String costType, BigDecimal totalAmount, List<String> skus);
}
