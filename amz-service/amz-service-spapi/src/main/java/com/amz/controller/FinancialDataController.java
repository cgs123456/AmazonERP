package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.client.FeesClient;
import com.amz.client.FinancesClient;
import com.amz.client.ReportsClient;
import com.amz.client.dto.FeeEstimate;
import com.amz.client.dto.FinancialEvent;
import com.amz.client.dto.ReportInfo;
import com.amz.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * SP-API 财务数据对外接口：结算事件、报表三段式、费用预估。
 * <p>
 * 供 finance 模块经 Feign 调用（与 product 模块经 SpapiFeedsClient 调 feeds 的
 * 跨服务模式一致）—— spapi 是 SP-API 取数层，finance 是业务层。
 * <p>
 * 鉴权：全部只读 + {@code @ShopScoped}（对齐 SpapiController 既有口径）。
 */
@RestController
@RequestMapping("/spapi/finance")
public class FinancialDataController {

    private static final Logger log = LoggerFactory.getLogger(FinancialDataController.class);

    @Autowired
    private ReportsClient reportsClient;

    @Autowired
    private FinancesClient financesClient;

    @Autowired
    private FeesClient feesClient;

    /**
     * 创建报表请求，返回 reportId（轮询节奏由调用方控制）。
     */
    @ShopScoped
    @PostMapping("/report/request")
    public Result<String> requestReport(@RequestParam Long shopId,
                                        @RequestParam String marketplaceId,
                                        @RequestParam String reportType,
                                        @RequestParam(required = false) String dataStartTime,
                                        @RequestParam(required = false) String dataEndTime) {
        try {
            String reportId = reportsClient.createReport(shopId, marketplaceId, reportType,
                    dataStartTime, dataEndTime);
            return Result.success(reportId);
        } catch (Exception e) {
            log.error("requestReport failed shopId={} reportType={}", shopId, reportType, e);
            return Result.failure("报表请求失败：" + e.getMessage());
        }
    }

    /**
     * 查询报表处理状态（DONE 时携带 documentId）。
     */
    @ShopScoped
    @GetMapping("/report/{reportId}")
    public Result<ReportInfo> getReport(@PathVariable String reportId,
                                        @RequestParam Long shopId) {
        try {
            return Result.success(reportsClient.getReport(shopId, reportId));
        } catch (Exception e) {
            log.error("getReport failed shopId={} reportId={}", shopId, reportId, e);
            return Result.failure("报表状态查询失败：" + e.getMessage());
        }
    }

    /**
     * 下载报表结果文档（自动按元数据解压，结算原表为 TSV 文本）。
     */
    @ShopScoped
    @GetMapping("/document/{documentId}")
    public Result<String> downloadDocument(@PathVariable String documentId,
                                           @RequestParam Long shopId) {
        try {
            return Result.success(reportsClient.downloadDocument(shopId, documentId));
        } catch (Exception e) {
            log.error("downloadDocument failed shopId={} documentId={}", shopId, documentId, e);
            return Result.failure("报表文档下载失败：" + e.getMessage());
        }
    }

    /**
     * 按入账时间窗口拉取结算事件（INCOME / REFUND / FEE / ADJUSTMENT 四类，有符号金额）。
     */
    @ShopScoped
    @GetMapping("/events")
    public Result<List<FinancialEvent>> listEvents(@RequestParam Long shopId,
                                                   @RequestParam(required = false) String postedAfter,
                                                   @RequestParam(required = false) String postedBefore) {
        try {
            return Result.success(financesClient.listFinancialEvents(shopId, postedAfter, postedBefore));
        } catch (Exception e) {
            log.error("listEvents failed shopId={}", shopId, e);
            return Result.failure("结算事件拉取失败：" + e.getMessage());
        }
    }

    /**
     * FBA 费用预估（佣金 + 配送费 + 杂项，供与实际扣费比对）。
     *
     * @param idType 标识类型：ASIN（默认）或 SKU —— 结算原表只带 SKU，费用比对场景传 SKU
     */
    @ShopScoped
    @PostMapping("/fees/estimate")
    public Result<FeeEstimate> estimateFees(@RequestParam Long shopId,
                                            @RequestParam String marketplaceId,
                                            @RequestParam(defaultValue = "ASIN") String idType,
                                            @RequestParam("asin") String idValue,
                                            @RequestParam(required = false) String sku,
                                            @RequestParam BigDecimal price,
                                            @RequestParam(defaultValue = "USD") String currency) {
        try {
            return Result.success(feesClient.estimateFbaFees(shopId, marketplaceId, idType, idValue,
                    sku, price, currency));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.error("estimateFees failed shopId={} idType={} idValue={}", shopId, idType, idValue, e);
            return Result.failure("费用预估失败：" + e.getMessage());
        }
    }
}
