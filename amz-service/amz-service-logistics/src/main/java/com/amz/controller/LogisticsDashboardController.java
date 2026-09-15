package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.dto.CarrierPerformanceDTO;
import com.amz.dto.DashboardOverview;
import com.amz.dto.FreightCostBoard;
import com.amz.dto.QuoteBoard;
import com.amz.dto.ReceiptBoard;
import com.amz.dto.ShipmentAlertDTO;
import com.amz.dto.TransferBoard;
import com.amz.dto.TrendPointDTO;
import com.amz.result.Result;
import com.amz.service.LogisticsDashboardService;
import com.amz.service.LogisticsOpsDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 物流看板聚合端点。
 * <p>
 * 拆成多个端点而非一个「大而全」的看板接口，是为了让前端能按标签页分别加载与刷新：
 * 概览与告警需要高频刷新，承运商表现与趋势是低频分析视图，
 * 合成一个接口会导致每次刷新都重算全部指标。
 * <p>
 * 全部为只读接口，故不加 {@code @RequireRole}（VIEWER 亦可查看看板）；
 * 但一律加 {@link ShopScoped}，保证店铺维度的越权防护。
 */
@RestController
@RequestMapping("/logistics/dashboard")
public class LogisticsDashboardController {

    @Autowired
    private LogisticsDashboardService logisticsDashboardService;

    @Autowired
    private LogisticsOpsDashboardService logisticsOpsDashboardService;

    /**
     * 看板概览。
     * GET /logistics/dashboard/overview?shopId=1
     */
    @ShopScoped
    @GetMapping("/overview")
    public Result<DashboardOverview> overview(@RequestParam Long shopId) {
        return Result.success(logisticsDashboardService.overview(shopId));
    }

    /**
     * 建单量 / 送达量趋势。
     * GET /logistics/dashboard/trend?shopId=1&days=30
     *
     * @param days 统计天数，默认 30；服务内会夹取到 7~180 天
     */
    @ShopScoped
    @GetMapping("/trend")
    public Result<List<TrendPointDTO>> trend(@RequestParam Long shopId,
                                             @RequestParam(defaultValue = "30") int days) {
        return Result.success(logisticsDashboardService.trend(shopId, days));
    }

    /**
     * 承运商时效与延误率对比。
     * GET /logistics/dashboard/carrier-performance?shopId=1
     */
    @ShopScoped
    @GetMapping("/carrier-performance")
    public Result<List<CarrierPerformanceDTO>> carrierPerformance(@RequestParam Long shopId) {
        return Result.success(logisticsDashboardService.carrierPerformance(shopId));
    }

    /**
     * 待处理货件清单（延误 / 异常 / 超期 / 临近到港 / 取数过期 / 缺运单号）。
     * GET /logistics/dashboard/alerts?shopId=1
     */
    @ShopScoped
    @GetMapping("/alerts")
    public Result<List<ShipmentAlertDTO>> alerts(@RequestParam Long shopId) {
        return Result.success(logisticsDashboardService.alerts(shopId));
    }

    // ==================== 运营子域看板 ====================
    // 与上面的「货件 / 轨迹」看板分开成四个端点，同样是为了按标签页加载：
    // 报价有效期与调拨卡单需要天天看，头程成本与签收差异通常是周月对账时才看。

    /**
     * 报价看板（有效性 / 覆盖面 / 航线竞争度）。
     * GET /logistics/dashboard/quotes?shopId=1
     */
    @ShopScoped
    @GetMapping("/quotes")
    public Result<QuoteBoard> quotes(@RequestParam Long shopId) {
        return Result.success(logisticsOpsDashboardService.quoteBoard(shopId));
    }

    /**
     * 调拨看板（状态分布 / 卡单风险）。
     * GET /logistics/dashboard/transfers?shopId=1
     */
    @ShopScoped
    @GetMapping("/transfers")
    public Result<TransferBoard> transfers(@RequestParam Long shopId) {
        return Result.success(logisticsOpsDashboardService.transferBoard(shopId));
    }

    /**
     * 头程成本看板（成本构成 / 分摊覆盖率）。
     * GET /logistics/dashboard/freight-cost?shopId=1
     */
    @ShopScoped
    @GetMapping("/freight-cost")
    public Result<FreightCostBoard> freightCost(@RequestParam Long shopId) {
        return Result.success(logisticsOpsDashboardService.freightCostBoard(shopId));
    }

    /**
     * 签收差异看板（少收多收 / 待处理差额）。
     * GET /logistics/dashboard/receipts?shopId=1
     */
    @ShopScoped
    @GetMapping("/receipts")
    public Result<ReceiptBoard> receipts(@RequestParam Long shopId) {
        return Result.success(logisticsOpsDashboardService.receiptBoard(shopId));
    }
}
