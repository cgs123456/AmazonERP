package com.amz.service.impl;

import com.amz.mapper.BusinessOverviewMapper;
import com.amz.mapper.InventoryTurnoverMapper;
import com.amz.mapper.ProfitDetailMapper;
import com.amz.mapper.SalesDailyMapper;
import com.amz.model.*;
import com.amz.service.ReportUpgradeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 报表升级服务实现。
 */
@Slf4j
@Service
public class ReportUpgradeServiceImpl implements ReportUpgradeService {

    @Autowired
    private ProfitDetailMapper profitDetailMapper;

    @Autowired
    private InventoryTurnoverMapper inventoryTurnoverMapper;

    @Autowired
    private SalesDailyMapper salesDailyMapper;

    @Autowired
    private BusinessOverviewMapper businessOverviewMapper;

    // ==================== 利润核算 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProfitDetail saveProfitDetail(ProfitDetail detail) {
        if (detail.getShopId() == null || detail.getAmazonOrderId() == null) {
            throw new IllegalArgumentException("店铺ID和订单号不能为空");
        }
        if (detail.getReportDate() == null) {
            detail.setReportDate(LocalDate.now());
        }
        // 计算利润
        calculateProfit(detail);
        profitDetailMapper.insert(detail);
        return detail;
    }

    @Override
    public List<ProfitDetail> listProfitDetails(Long shopId, String asin, String startDate, String endDate) {
        LambdaQueryWrapper<ProfitDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProfitDetail::getShopId, shopId);
        if (asin != null && !asin.isBlank()) {
            wrapper.eq(ProfitDetail::getAsin, asin);
        }
        if (startDate != null) {
            wrapper.ge(ProfitDetail::getReportDate, LocalDate.parse(startDate));
        }
        if (endDate != null) {
            wrapper.le(ProfitDetail::getReportDate, LocalDate.parse(endDate));
        }
        wrapper.orderByDesc(ProfitDetail::getReportDate);
        return profitDetailMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> profitSummaryByAsin(Long shopId, String startDate, String endDate) {
        LambdaQueryWrapper<ProfitDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProfitDetail::getShopId, shopId);
        if (startDate != null) {
            wrapper.ge(ProfitDetail::getReportDate, LocalDate.parse(startDate));
        }
        if (endDate != null) {
            wrapper.le(ProfitDetail::getReportDate, LocalDate.parse(endDate));
        }
        List<ProfitDetail> details = profitDetailMapper.selectList(wrapper);

        // 按 ASIN 聚合
        Map<String, List<ProfitDetail>> byAsin = details.stream()
                .collect(Collectors.groupingBy(ProfitDetail::getAsin));

        List<Map<String, Object>> asinSummaries = new ArrayList<>();
        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalNetProfit = BigDecimal.ZERO;

        for (Map.Entry<String, List<ProfitDetail>> entry : byAsin.entrySet()) {
            List<ProfitDetail> group = entry.getValue();
            BigDecimal sales = group.stream().map(ProfitDetail::getProductSales).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cost = group.stream().map(ProfitDetail::getProductCost).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal adCost = group.stream().map(ProfitDetail::getAdvertisingCost).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fees = group.stream().map(d -> {
                BigDecimal f = BigDecimal.ZERO;
                if (d.getFbaFees() != null) f = f.add(d.getFbaFees());
                if (d.getReferralFee() != null) f = f.add(d.getReferralFee());
                if (d.getVariableClosingFee() != null) f = f.add(d.getVariableClosingFee());
                if (d.getStorageFee() != null) f = f.add(d.getStorageFee());
                return f;
            }).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grossProfit = group.stream().map(ProfitDetail::getGrossProfit).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal netProfit = group.stream().map(ProfitDetail::getNetProfit).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("asin", entry.getKey());
            summary.put("orderCount", group.size());
            summary.put("totalSales", sales);
            summary.put("totalCost", cost);
            summary.put("totalAdSpend", adCost);
            summary.put("totalFees", fees);
            summary.put("grossProfit", grossProfit);
            summary.put("netProfit", netProfit);
            summary.put("margin", sales.compareTo(BigDecimal.ZERO) > 0
                    ? netProfit.multiply(new BigDecimal("100")).divide(sales, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);

            asinSummaries.add(summary);
            totalSales = totalSales.add(sales);
            totalCost = totalCost.add(cost);
            totalNetProfit = totalNetProfit.add(netProfit);
        }

        // 按净利润降序
        asinSummaries.sort((a, b) -> ((BigDecimal) b.get("netProfit")).compareTo((BigDecimal) a.get("netProfit")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("totalOrders", details.size());
        result.put("totalSales", totalSales);
        result.put("totalCost", totalCost);
        result.put("totalNetProfit", totalNetProfit);
        result.put("overallMargin", totalSales.compareTo(BigDecimal.ZERO) > 0
                ? totalNetProfit.multiply(new BigDecimal("100")).divide(totalSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        result.put("asinSummaries", asinSummaries);
        return result;
    }

    // ==================== 库存周转 ====================

    @Override
    public InventoryTurnover saveInventoryTurnover(InventoryTurnover turnover) {
        if (turnover.getReportDate() == null) {
            turnover.setReportDate(LocalDate.now());
        }
        // 计算周转率
        if (turnover.getAvgInventoryValue() != null && turnover.getAvgInventoryValue().compareTo(BigDecimal.ZERO) > 0
                && turnover.getCogs() != null) {
            turnover.setTurnoverRate(turnover.getCogs()
                    .divide(turnover.getAvgInventoryValue(), 2, RoundingMode.HALF_UP));
        }
        inventoryTurnoverMapper.insert(turnover);
        return turnover;
    }

    @Override
    public List<InventoryTurnover> listInventoryTurnover(Long shopId, String asin) {
        LambdaQueryWrapper<InventoryTurnover> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryTurnover::getShopId, shopId);
        if (asin != null && !asin.isBlank()) {
            wrapper.eq(InventoryTurnover::getAsin, asin);
        }
        wrapper.orderByDesc(InventoryTurnover::getReportDate);
        return inventoryTurnoverMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> deadStockAnalysis(Long shopId) {
        LambdaQueryWrapper<InventoryTurnover> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryTurnover::getShopId, shopId)
               .gt(InventoryTurnover::getOverstockDays, 60)
               .orderByDesc(InventoryTurnover::getDeadStockValue);
        List<InventoryTurnover> deadStocks = inventoryTurnoverMapper.selectList(wrapper);

        BigDecimal totalDeadStockValue = deadStocks.stream()
                .map(InventoryTurnover::getDeadStockValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("deadStockCount", deadStocks.size());
        result.put("totalDeadStockValue", totalDeadStockValue);
        result.put("details", deadStocks);
        return result;
    }

    // ==================== 销售趋势 ====================

    @Override
    public SalesDaily saveSalesDaily(SalesDaily salesDaily) {
        if (salesDaily.getReportDate() == null) {
            salesDaily.setReportDate(LocalDate.now());
        }
        // 计算净销量/净销售额
        int ordered = salesDaily.getUnitsOrdered() != null ? salesDaily.getUnitsOrdered() : 0;
        int refunded = salesDaily.getUnitsRefunded() != null ? salesDaily.getUnitsRefunded() : 0;
        salesDaily.setNetUnits(ordered - refunded);

        BigDecimal gross = salesDaily.getGrossSales() != null ? salesDaily.getGrossSales() : BigDecimal.ZERO;
        BigDecimal refundAmt = salesDaily.getRefundAmount() != null ? salesDaily.getRefundAmount() : BigDecimal.ZERO;
        salesDaily.setNetSales(gross.subtract(refundAmt));

        // 计算转化率
        if (salesDaily.getSessions() != null && salesDaily.getSessions() > 0) {
            salesDaily.setConversionRate(BigDecimal.valueOf(ordered)
                    .multiply(new BigDecimal("100"))
                    .divide(BigDecimal.valueOf(salesDaily.getSessions()), 2, RoundingMode.HALF_UP));
        }

        salesDailyMapper.insert(salesDaily);
        return salesDaily;
    }

    @Override
    public List<SalesDaily> listSalesDaily(Long shopId, String asin, String startDate, String endDate) {
        LambdaQueryWrapper<SalesDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SalesDaily::getShopId, shopId);
        if (asin != null && !asin.isBlank()) {
            wrapper.eq(SalesDaily::getAsin, asin);
        }
        if (startDate != null) {
            wrapper.ge(SalesDaily::getReportDate, LocalDate.parse(startDate));
        }
        if (endDate != null) {
            wrapper.le(SalesDaily::getReportDate, LocalDate.parse(endDate));
        }
        wrapper.orderByAsc(SalesDaily::getReportDate);
        return salesDailyMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> salesComparison(Long shopId, String asin, String currentDate, Integer compareDays) {
        LocalDate endDate = currentDate != null ? LocalDate.parse(currentDate) : LocalDate.now();
        LocalDate startDate = endDate.minusDays(compareDays != null ? compareDays : 30);
        LocalDate prevEndDate = startDate.minusDays(1);
        LocalDate prevStartDate = prevEndDate.minusDays(compareDays != null ? compareDays : 30);

        LambdaQueryWrapper<SalesDaily> currentWrapper = new LambdaQueryWrapper<>();
        currentWrapper.eq(SalesDaily::getShopId, shopId)
                      .ge(SalesDaily::getReportDate, startDate)
                      .le(SalesDaily::getReportDate, endDate);
        if (asin != null && !asin.isBlank()) {
            currentWrapper.eq(SalesDaily::getAsin, asin);
        }
        List<SalesDaily> current = salesDailyMapper.selectList(currentWrapper);

        LambdaQueryWrapper<SalesDaily> prevWrapper = new LambdaQueryWrapper<>();
        prevWrapper.eq(SalesDaily::getShopId, shopId)
                   .ge(SalesDaily::getReportDate, prevStartDate)
                   .le(SalesDaily::getReportDate, prevEndDate);
        if (asin != null && !asin.isBlank()) {
            prevWrapper.eq(SalesDaily::getAsin, asin);
        }
        List<SalesDaily> previous = salesDailyMapper.selectList(prevWrapper);

        int currentUnits = current.stream().mapToInt(d -> d.getNetUnits() != null ? d.getNetUnits() : 0).sum();
        int prevUnits = previous.stream().mapToInt(d -> d.getNetUnits() != null ? d.getNetUnits() : 0).sum();
        BigDecimal currentSales = current.stream().map(SalesDaily::getNetSales).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal prevSales = previous.stream().map(SalesDaily::getNetSales).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("asin", asin);
        result.put("currentPeriod", startDate + " ~ " + endDate);
        result.put("previousPeriod", prevStartDate + " ~ " + prevEndDate);
        result.put("currentUnits", currentUnits);
        result.put("previousUnits", prevUnits);
        result.put("unitGrowth", prevUnits > 0 ? BigDecimal.valueOf((currentUnits - prevUnits) * 100.0 / prevUnits).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        result.put("currentSales", currentSales);
        result.put("previousSales", prevSales);
        result.put("salesGrowth", prevSales.compareTo(BigDecimal.ZERO) > 0
                ? currentSales.subtract(prevSales).multiply(new BigDecimal("100")).divide(prevSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        return result;
    }

    // ==================== 经营概览 ====================

    @Override
    public BusinessOverview saveBusinessOverview(BusinessOverview overview) {
        if (overview.getReportDate() == null) {
            overview.setReportDate(LocalDate.now());
        }
        // 计算派生指标
        if (overview.getTotalOrders() != null && overview.getTotalOrders() > 0 && overview.getTotalSales() != null) {
            overview.setAvgOrderValue(overview.getTotalSales().divide(BigDecimal.valueOf(overview.getTotalOrders()), 2, RoundingMode.HALF_UP));
        }
        if (overview.getTotalSales() != null && overview.getTotalSales().compareTo(BigDecimal.ZERO) > 0 && overview.getNetProfit() != null) {
            overview.setProfitMargin(overview.getNetProfit().multiply(new BigDecimal("100")).divide(overview.getTotalSales(), 2, RoundingMode.HALF_UP));
        }
        if (overview.getTotalOrders() != null && overview.getTotalOrders() > 0 && overview.getTotalRefunds() != null) {
            overview.setRefundRate(BigDecimal.valueOf(overview.getTotalRefunds()).multiply(new BigDecimal("100")).divide(BigDecimal.valueOf(overview.getTotalOrders()), 2, RoundingMode.HALF_UP));
        }
        businessOverviewMapper.insert(overview);
        return overview;
    }

    @Override
    public List<BusinessOverview> listBusinessOverview(Long shopId, String startDate, String endDate) {
        LambdaQueryWrapper<BusinessOverview> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BusinessOverview::getShopId, shopId);
        if (startDate != null) {
            wrapper.ge(BusinessOverview::getReportDate, LocalDate.parse(startDate));
        }
        if (endDate != null) {
            wrapper.le(BusinessOverview::getReportDate, LocalDate.parse(endDate));
        }
        wrapper.orderByDesc(BusinessOverview::getReportDate);
        return businessOverviewMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> shopDashboard(Long shopId) {
        LocalDate today = LocalDate.now();
        LocalDate sevenDaysAgo = today.minusDays(7);
        LocalDate thirtyDaysAgo = today.minusDays(30);

        // 最近 7 天经营概览
        LambdaQueryWrapper<BusinessOverview> recentWrapper = new LambdaQueryWrapper<>();
        recentWrapper.eq(BusinessOverview::getShopId, shopId)
                     .ge(BusinessOverview::getReportDate, sevenDaysAgo)
                     .orderByDesc(BusinessOverview::getReportDate);
        List<BusinessOverview> recentOverviews = businessOverviewMapper.selectList(recentWrapper);

        // 最近 30 天销售汇总
        LambdaQueryWrapper<SalesDaily> salesWrapper = new LambdaQueryWrapper<>();
        salesWrapper.eq(SalesDaily::getShopId, shopId)
                    .ge(SalesDaily::getReportDate, thirtyDaysAgo)
                    .orderByAsc(SalesDaily::getReportDate);
        List<SalesDaily> salesData = salesDailyMapper.selectList(salesWrapper);

        // 利润汇总
        Map<String, Object> profitSummary = profitSummaryByAsin(shopId, thirtyDaysAgo.toString(), today.toString());

        // 呆滞库存
        Map<String, Object> deadStock = deadStockAnalysis(shopId);

        // 汇总
        BigDecimal totalSales7d = recentOverviews.stream().map(BusinessOverview::getTotalSales).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfit7d = recentOverviews.stream().map(BusinessOverview::getNetProfit).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalOrders7d = recentOverviews.stream().mapToInt(o -> o.getTotalOrders() != null ? o.getTotalOrders() : 0).sum();
        BigDecimal totalAdSpend7d = recentOverviews.stream().map(BusinessOverview::getTotalAdSpend).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("shopId", shopId);
        dashboard.put("reportDate", today.toString());

        // 核心指标
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("totalSales7d", totalSales7d);
        kpi.put("totalProfit7d", totalProfit7d);
        kpi.put("totalOrders7d", totalOrders7d);
        kpi.put("totalAdSpend7d", totalAdSpend7d);
        kpi.put("profitMargin7d", totalSales7d.compareTo(BigDecimal.ZERO) > 0
                ? totalProfit7d.multiply(new BigDecimal("100")).divide(totalSales7d, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        dashboard.put("kpi", kpi);

        // 销售趋势
        List<Map<String, Object>> salesTrend = salesData.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.getReportDate().toString());
            m.put("netUnits", d.getNetUnits());
            m.put("netSales", d.getNetSales());
            m.put("sessions", d.getSessions());
            m.put("conversionRate", d.getConversionRate());
            return m;
        }).collect(Collectors.toList());
        dashboard.put("salesTrend30d", salesTrend);

        // 利润汇总
        dashboard.put("profitSummary30d", profitSummary);

        // 呆滞库存
        dashboard.put("deadStockAnalysis", deadStock);

        return dashboard;
    }

    // ==================== 工具方法 ====================

    private void calculateProfit(ProfitDetail detail) {
        // 收入
        BigDecimal revenue = BigDecimal.ZERO;
        if (detail.getProductSales() != null) revenue = revenue.add(detail.getProductSales());
        if (detail.getShippingCredits() != null) revenue = revenue.add(detail.getShippingCredits());
        if (detail.getPromotionalRebates() != null) revenue = revenue.subtract(detail.getPromotionalRebates());

        // 成本
        BigDecimal cogs = BigDecimal.ZERO;
        if (detail.getProductCost() != null) cogs = cogs.add(detail.getProductCost());
        BigDecimal fulfillment = BigDecimal.ZERO;
        if (detail.getFbaFees() != null) fulfillment = fulfillment.add(detail.getFbaFees());
        BigDecimal commission = BigDecimal.ZERO;
        if (detail.getReferralFee() != null) commission = commission.add(detail.getReferralFee());
        if (detail.getVariableClosingFee() != null) commission = commission.add(detail.getVariableClosingFee());

        // 毛利 = 收入 - 采购 - 履约 - 佣金
        BigDecimal grossProfit = revenue.subtract(cogs).subtract(fulfillment).subtract(commission);
        if (detail.getInboundFreight() != null) grossProfit = grossProfit.subtract(detail.getInboundFreight());
        if (detail.getInboundDuty() != null) grossProfit = grossProfit.subtract(detail.getInboundDuty());
        detail.setGrossProfit(grossProfit);

        // 净利 = 毛利 - 广告 - VAT - 仓储 - 其他
        BigDecimal netProfit = grossProfit;
        if (detail.getAdvertisingCost() != null) netProfit = netProfit.subtract(detail.getAdvertisingCost());
        if (detail.getVatTax() != null) netProfit = netProfit.subtract(detail.getVatTax());
        if (detail.getStorageFee() != null) netProfit = netProfit.subtract(detail.getStorageFee());
        if (detail.getOtherFees() != null) netProfit = netProfit.subtract(detail.getOtherFees());
        detail.setNetProfit(netProfit);

        // 利润率
        if (revenue.compareTo(BigDecimal.ZERO) > 0) {
            detail.setMargin(netProfit.multiply(new BigDecimal("100")).divide(revenue, 2, RoundingMode.HALF_UP));
        }
    }
}
