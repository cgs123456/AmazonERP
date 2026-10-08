package com.amz.service;

import com.amz.model.BusinessOverview;
import com.amz.model.InventoryTurnover;
import com.amz.model.ProfitDetail;
import com.amz.model.SalesDaily;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

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

    /**
     * 查询利润明细列表（keyset 游标分页）。
     * <p>
     * 2026-10-07 之前是全量返回：明细随聚合逐日增长，无上限读取迟早会把整表灌进一次响应。
     * 分页口径与利润快照列表一致（(report_date,id) 复合游标 + 探测行判截断）。
     */
    PageResult<ProfitDetail> listProfitDetails(Long shopId, String asin, String startDate, String endDate,
                                               PageRequest page);

    /** 利润汇总（按 ASIN 维度） */
    Map<String, Object> profitSummaryByAsin(Long shopId, String startDate, String endDate);

    // ===== 库存周转 =====

    /** 保存库存周转数据 */
    InventoryTurnover saveInventoryTurnover(InventoryTurnover turnover);

    /**
     * 查询库存周转列表（keyset 游标分页，2026-10-07 随 /v2 列表统一收口，见
     * {@link #listProfitDetails} 的注释）。
     */
    PageResult<InventoryTurnover> listInventoryTurnover(Long shopId, String asin, PageRequest page);

    /** 呆滞库存分析 */
    Map<String, Object> deadStockAnalysis(Long shopId);

    // ===== 销售趋势 =====

    /** 保存销售日报 */
    SalesDaily saveSalesDaily(SalesDaily salesDaily);

    /**
     * 查询销售趋势（keyset 游标分页）。展示口径保持日期升序不变，
     * 因此游标条件与降序列表方向相反（取 (report_date,id) 之上界）。
     */
    PageResult<SalesDaily> listSalesDaily(Long shopId, String asin, String startDate, String endDate,
                                          PageRequest page);

    /** 销售环比/同比 */
    Map<String, Object> salesComparison(Long shopId, String asin, String currentDate, Integer compareDays);

    // ===== 经营概览 =====

    /** 保存经营概览 */
    BusinessOverview saveBusinessOverview(BusinessOverview overview);

    /**
     * 查询经营概览（keyset 游标分页）。
     */
    PageResult<BusinessOverview> listBusinessOverview(Long shopId, String startDate, String endDate,
                                                      PageRequest page);

    /** 店铺综合看板（聚合多维度数据） */
    Map<String, Object> shopDashboard(Long shopId);
}
