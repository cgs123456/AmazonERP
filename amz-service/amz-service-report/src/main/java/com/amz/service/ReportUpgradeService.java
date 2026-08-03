package com.amz.service;

import com.amz.model.BusinessOverview;
import com.amz.model.InventoryTurnover;
import com.amz.model.ProfitDetail;
import com.amz.model.SalesDaily;

import java.util.List;
import java.util.Map;

/**
 * 报表升级服务接口。
 * <p>
 * 覆盖：利润核算/库存周转/销售趋势/经营概览
 */
public interface ReportUpgradeService {

    // ===== 利润核算 =====

    /** 保存利润明细 */
    ProfitDetail saveProfitDetail(ProfitDetail detail);

    /** 查询利润明细列表 */
    List<ProfitDetail> listProfitDetails(Long shopId, String asin, String startDate, String endDate);

    /** 利润汇总（按 ASIN 维度） */
    Map<String, Object> profitSummaryByAsin(Long shopId, String startDate, String endDate);

    // ===== 库存周转 =====

    /** 保存库存周转数据 */
    InventoryTurnover saveInventoryTurnover(InventoryTurnover turnover);

    /** 查询库存周转列表 */
    List<InventoryTurnover> listInventoryTurnover(Long shopId, String asin);

    /** 呆滞库存分析 */
    Map<String, Object> deadStockAnalysis(Long shopId);

    // ===== 销售趋势 =====

    /** 保存销售日报 */
    SalesDaily saveSalesDaily(SalesDaily salesDaily);

    /** 查询销售趋势 */
    List<SalesDaily> listSalesDaily(Long shopId, String asin, String startDate, String endDate);

    /** 销售环比/同比 */
    Map<String, Object> salesComparison(Long shopId, String asin, String currentDate, Integer compareDays);

    // ===== 经营概览 =====

    /** 保存经营概览 */
    BusinessOverview saveBusinessOverview(BusinessOverview overview);

    /** 查询经营概览 */
    List<BusinessOverview> listBusinessOverview(Long shopId, String startDate, String endDate);

    /** 店铺综合看板（聚合多维度数据） */
    Map<String, Object> shopDashboard(Long shopId);
}
