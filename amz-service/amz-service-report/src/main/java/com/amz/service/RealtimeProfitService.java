package com.amz.service;

import com.amz.model.CostAllocation;
import com.amz.model.ProfitSnapshot;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 实时利润核算服务。
 */
public interface RealtimeProfitService {

    // ==================== 利润快照 ====================
    ProfitSnapshot snapshotProfit(Long shopId, String sku, String asin);
    PageResult<ProfitSnapshot> listSnapshots(Long shopId, String sku, String startTime, String endTime,
                                             PageRequest page);
    Map<String, Object> profitTrend(Long shopId, String sku, String asin, Integer hours);
    Map<String, Object> profitSummary(Long shopId, String startTime, String endTime);

    // ==================== 费用分摊 ====================
    CostAllocation saveAllocation(CostAllocation allocation);
    PageResult<CostAllocation> listAllocations(Long shopId, String costType, String startDate, String endDate,
                                                PageRequest page);
    /**
     * 记录一笔成本分摊。
     *
     * @param entries   每项写 {@code SKU} 或 {@code SKU:金额}。带金额即按给定金额入账（头程这类
     *                  成本在采购域已按数量摊好，重新均摊会把金额算歪），给定金额之和必须等于
     *                  totalAmount；全不带金额时退回均摊。
     * @param sourceRef 业务来源标识（如货件号）。给了就幂等：同来源不重复入账，直接返回已入账明细。
     * @param currency  币种。分摊金额本身没有币种线索（货件成本字段不记币种），必须由调用方声明。
     */
    Map<String, BigDecimal> allocateCost(Long shopId, String costType, BigDecimal totalAmount, List<String> entries,
                                         String sourceRef, String currency);
}
