package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.BusinessOverview;
import com.amz.model.InventoryTurnover;
import com.amz.model.ProfitDetail;
import com.amz.model.SalesDaily;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.amz.service.ReportUpgradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 报表升级 REST 端点。
 * <p>
 * 覆盖：利润核算/库存周转/销售趋势/经营概览
 */
@RestController
@RequestMapping("/report/v2")
public class ReportUpgradeController {

    @Autowired
    private ReportUpgradeService reportUpgradeService;

    // ==================== 利润核算 ====================

    /** 保存利润明细 */
    @ShopScoped
    @PostMapping("/profit")
    @RequireRole({"OPERATOR", "ADMIN"})
    public Result<ProfitDetail> saveProfit(@RequestBody ProfitDetail detail) {
        return Result.success(reportUpgradeService.saveProfitDetail(detail));
    }

    /**
     * 查询利润明细列表（keyset 游标分页）。
     * <p>
     * 2026-10-07 从无界全量读改为 size/cursor：明细表随聚合逐日增长，全量返回迟早
     * 把整表灌进一次响应；分页口径与 /report/profit/snapshot/list 一致。
     */
    /**
     * 利润明细 CSV 导出（P1.5）：复用列表口径，游标翻页直到取尽。
     * 有 10 万行硬上限，触顶写显式截断说明行——静默截断会把「部分」当「全部」。
     */
    @ShopScoped
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @OperLog(module = "报表导出", action = "导出", description = "导出利润明细 CSV")
    @GetMapping(value = "/profit/export/{shopId}", produces = "text/csv; charset=UTF-8")
    public void exportProfit(@PathVariable Long shopId,
                             @RequestParam(required = false) String asin,
                             @RequestParam(required = false) String startDate,
                             @RequestParam(required = false) String endDate,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        java.io.PrintWriter writer = com.amz.util.CsvExportUtil.begin(
                response, "profit-detail-" + shopId + ".csv");
        int maxRows = com.amz.util.CsvExportUtil.DEFAULT_MAX_ROWS;
        com.amz.util.CsvExportUtil.row(writer, java.util.List.of(
                "报表日期", "Amazon订单号", "ASIN", "SKU", "销售额", "采购成本", "FBA费用",
                "佣金", "仓储费", "广告费", "VAT", "其他费用", "退款", "毛利", "净利", "币种"));
        int rows = 0;
        String cursor = null;
        boolean truncated = false;
        try {
            while (rows < maxRows) {
                com.amz.result.PageResult<com.amz.model.ProfitDetail> page =
                        reportUpgradeService.listProfitDetails(shopId, asin, startDate, endDate,
                                com.amz.result.PageRequest.of(500, cursor));
                for (com.amz.model.ProfitDetail d : page.items()) {
                    com.amz.util.CsvExportUtil.row(writer, java.util.List.of(
                            d.getReportDate(), d.getAmazonOrderId(), d.getAsin(), d.getSku(),
                            d.getProductSales(), d.getProductCost(), d.getFbaFees(),
                            d.getReferralFee(), d.getStorageFee(), d.getAdvertisingCost(),
                            d.getVatTax(), d.getOtherFees(), d.getRefundCost(),
                            d.getGrossProfit(), d.getNetProfit(), d.getCurrency()));
                    rows++;
                    if (rows >= maxRows) {
                        truncated = true;
                        break;
                    }
                }
                if (truncated || !page.hasMore()) {
                    break;
                }
                cursor = page.nextCursor();
                if (cursor == null || cursor.isBlank()) {
                    break;
                }
            }
            if (truncated) {
                com.amz.util.CsvExportUtil.truncatedRow(writer, maxRows);
            }
        } finally {
            writer.flush();
        }
    }

    @ShopScoped
    @GetMapping("/profit/list/{shopId}")
    public Result<List<ProfitDetail>> listProfit(@PathVariable Long shopId,
                                                   @RequestParam(required = false) String asin,
                                                   @RequestParam(required = false) String startDate,
                                                   @RequestParam(required = false) String endDate,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String cursor) {
        return Result.paged(reportUpgradeService.listProfitDetails(shopId, asin, startDate, endDate,
                PageRequest.of(size, cursor)));
    }

    /** 利润汇总（按 ASIN 维度） */
    @ShopScoped
    @GetMapping("/profit/summary/{shopId}")
    public Result<Map<String, Object>> profitSummary(@PathVariable Long shopId,
                                                      @RequestParam(required = false) String startDate,
                                                      @RequestParam(required = false) String endDate) {
        return Result.success(reportUpgradeService.profitSummaryByAsin(shopId, startDate, endDate));
    }

    // ==================== 库存周转 ====================

    /** 保存库存周转数据 */
    @ShopScoped
    @PostMapping("/inventory-turnover")
    @RequireRole({"OPERATOR", "ADMIN"})
    public Result<InventoryTurnover> saveTurnover(@RequestBody InventoryTurnover turnover) {
        return Result.success(reportUpgradeService.saveInventoryTurnover(turnover));
    }

    /**
     * 查询库存周转列表（keyset 游标分页，2026-10-07 随 /v2 列表统一收口）。
     */
    @ShopScoped
    @GetMapping("/inventory-turnover/list/{shopId}")
    public Result<List<InventoryTurnover>> listTurnover(@PathVariable Long shopId,
                                                         @RequestParam(required = false) String asin,
                                                         @RequestParam(required = false) Integer size,
                                                         @RequestParam(required = false) String cursor) {
        return Result.paged(reportUpgradeService.listInventoryTurnover(shopId, asin,
                PageRequest.of(size, cursor)));
    }

    /** 呆滞库存分析 */
    @ShopScoped
    @GetMapping("/inventory-turnover/dead-stock/{shopId}")
    public Result<Map<String, Object>> deadStock(@PathVariable Long shopId) {
        return Result.success(reportUpgradeService.deadStockAnalysis(shopId));
    }

    // ==================== 销售趋势 ====================

    /** 保存销售日报 */
    @ShopScoped
    @PostMapping("/sales-daily")
    @RequireRole({"OPERATOR", "ADMIN"})
    public Result<SalesDaily> saveSalesDaily(@RequestBody SalesDaily salesDaily) {
        return Result.success(reportUpgradeService.saveSalesDaily(salesDaily));
    }

    /**
     * 查询销售趋势（keyset 游标分页；展示按日期升序，游标取上界，载荷格式与其余列表一致）。
     */
    @ShopScoped
    @GetMapping("/sales-daily/list/{shopId}")
    public Result<List<SalesDaily>> listSalesDaily(@PathVariable Long shopId,
                                                    @RequestParam(required = false) String asin,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String cursor) {
        return Result.paged(reportUpgradeService.listSalesDaily(shopId, asin, startDate, endDate,
                PageRequest.of(size, cursor)));
    }

    /** 销售环比/同比 */
    @ShopScoped
    @GetMapping("/sales-daily/comparison/{shopId}")
    public Result<Map<String, Object>> salesComparison(@PathVariable Long shopId,
                                                        @RequestParam(required = false) String asin,
                                                        @RequestParam(required = false) String currentDate,
                                                        @RequestParam(defaultValue = "30") Integer compareDays) {
        return Result.success(reportUpgradeService.salesComparison(shopId, asin, currentDate, compareDays));
    }

    // ==================== 经营概览 ====================

    /** 保存经营概览 */
    @ShopScoped
    @PostMapping("/business-overview")
    @RequireRole({"OPERATOR", "ADMIN"})
    public Result<BusinessOverview> saveOverview(@RequestBody BusinessOverview overview) {
        return Result.success(reportUpgradeService.saveBusinessOverview(overview));
    }

    /**
     * 查询经营概览（keyset 游标分页）。
     */
    @ShopScoped
    @GetMapping("/business-overview/list/{shopId}")
    public Result<List<BusinessOverview>> listOverview(@PathVariable Long shopId,
                                                        @RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate,
                                                        @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String cursor) {
        return Result.paged(reportUpgradeService.listBusinessOverview(shopId, startDate, endDate,
                PageRequest.of(size, cursor)));
    }

    /** 店铺综合看板 */
    @ShopScoped
    @GetMapping("/dashboard/{shopId}")
    public Result<Map<String, Object>> dashboard(@PathVariable Long shopId) {
        return Result.success(reportUpgradeService.shopDashboard(shopId));
    }
}
