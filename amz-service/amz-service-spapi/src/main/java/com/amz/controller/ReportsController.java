package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.ReportsClient;
import com.amz.client.dto.ReportInfo;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
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

/**
 * SP-API 通用报表三段式接口（createReport / getReport / downloadDocument）。
 * <p>
 * <b>与 {@code /spapi/finance/report/*} 的区别</b>：那条是 finance 专用（结算报表），
 * 白名单只放行 finance。本端点服务于<b>业务域自取报表</b>——当前是 logistics 拉
 * FBA 客户退货报表（{@code GET_FBA_FULFILLMENT_CUSTOMER_RETURNS_DATA}）。
 * 把报表能力按「谁用」拆开而不是让 logistics 去借 finance 的通道，是为了让
 * 内部访问白名单保持最小：logistics 不该因此获得财务报表的取数权。
 * <p>
 * <b>reportType 不做白名单</b>：官方对 reportType 定义为自由字符串（无 enum），
 * 由调用方按需传；这里只做「非空」与「必须为 Amazon 官方值」的说明性校验，
 * 不硬编码允许集合（否则每次新增报表类型都要改 spapi，而 spapi 不该知道业务需求）。
 * <p>
 * 鉴权：{@code @ShopScoped} + {@code @InternalServiceAccess} 白名单；
 * 三个方法都是只读语义（createReport 虽为 POST，但不改本地数据）。
 */
@RestController
@RequestMapping("/spapi/reports")
public class ReportsController {

    private static final Logger log = LoggerFactory.getLogger(ReportsController.class);

    @Autowired
    private ReportsClient reportsClient;

    /**
     * 创建报表请求，返回 reportId（轮询节奏由调用方控制）。
     */
    @InternalServiceAccess("amz-service-logistics")
    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @OperLog(module = "报表", action = "请求报表", description = "请求报表（需追责）")
    @PostMapping("/request")
    public Result<String> requestReport(@RequestParam Long shopId,
                                        @RequestParam String marketplaceId,
                                        @RequestParam String reportType,
                                        @RequestParam(required = false) String dataStartTime,
                                        @RequestParam(required = false) String dataEndTime) {
        if (reportType == null || reportType.isBlank()) {
            return Result.failure("reportType 不能为空",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        try {
            return Result.success(reportsClient.createReport(shopId, marketplaceId, reportType,
                    dataStartTime, dataEndTime));
        } catch (Exception e) {
            log.error("requestReport failed shopId={} reportType={}", shopId, reportType, e);
            return Result.failure("报表请求失败：" + ErrorSummary.of(e), ErrorSummary.toApiError(e));
        }
    }

    /**
     * 查询报表处理状态（DONE 时携带 documentId）。
     */
    @InternalServiceAccess("amz-service-logistics")
    @ShopScoped
    @GetMapping("/{reportId}")
    public Result<ReportInfo> getReport(@PathVariable String reportId,
                                        @RequestParam Long shopId) {
        try {
            return Result.success(reportsClient.getReport(shopId, reportId));
        } catch (Exception e) {
            log.error("getReport failed shopId={} reportId={}", shopId, reportId, e);
            return Result.failure("报表状态查询失败：" + ErrorSummary.of(e), ErrorSummary.toApiError(e));
        }
    }

    /**
     * 下载报表结果文档（自动按元数据解压）。
     */
    @InternalServiceAccess("amz-service-logistics")
    @ShopScoped
    @GetMapping("/document/{documentId}")
    public Result<String> downloadDocument(@PathVariable String documentId,
                                           @RequestParam Long shopId) {
        try {
            return Result.success(reportsClient.downloadDocument(shopId, documentId));
        } catch (Exception e) {
            log.error("downloadDocument failed shopId={} documentId={}", shopId, documentId, e);
            return Result.failure("报表文档下载失败：" + ErrorSummary.of(e), ErrorSummary.toApiError(e));
        }
    }
}
