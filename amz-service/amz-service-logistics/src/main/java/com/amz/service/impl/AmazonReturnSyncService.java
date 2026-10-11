package com.amz.service.impl;

import com.amz.client.SpApiReportsClient;
import com.amz.client.dto.RemoteReportInfo;
import com.amz.exception.CodeErrorException;
import com.amz.model.ReturnOrder;
import com.amz.parse.CustomerReturnsParser;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Amazon FBA 客户退货自动导入（Reports API → 退货单）。
 * <p>
 * <b>存在动因</b>：退货单此前只能手工登记。Amazon 的 FBA 客户退货数据只能通过
 * Reports API 的 {@code GET_FBA_FULFILLMENT_CUSTOMER_RETURNS_DATA} 拉取
 * （13 列：return-date/order-id/sku/asin/fnsku/product-name/quantity/
 * fulfillment-center-id/detailed-disposition/reason/status/license-plate-number/
 * customer-comments），本服务把这条链路接通。
 * <p>
 * <b>FBM（卖家自发货）没有对应报表</b>：SP-API 只提供 FBA 客户退货报表，
 * 卖家自发货的退货不在平台侧留档（退货地址是卖家自己的）。因此
 * {@code AMAZON_FBM} 只能继续手工登记——本服务<b>不</b>假装能自动拉 FBM 退货。
 * <p>
 * <b>幂等</b>：导入按 {@code external_key} 去重（见 {@code ReturnOrderService.importAmazonReturn}），
 * 因此同一窗口重复跑不会重复建单、不会重复回补库存；窗口重叠是常态（报表按批次下发），
 * 幂等是这条链路能反复执行的前提。
 * <p>
 * <b>报表三段式</b>：createReport → 轮询 getReport 至 DONE/FATAL → downloadDocument。
 * 轮询有<b>次数与挂钟双上限</b>（照抄结算同步的纪律），超时抛异常而不是静默返回空——
 * 「没拉到」与「本期没有退货」是两件事。
 */
@Slf4j
@Service
public class AmazonReturnSyncService {

    /** 轮询次数上限。 */
    @Value("${amz.logistics.return-sync.max-poll-attempts:15}")
    private int maxPollAttempts;

    /** 轮询间隔（毫秒）。 */
    @Value("${amz.logistics.return-sync.poll-interval-ms:2000}")
    private long pollIntervalMs;

    /** 轮询挂钟上限（毫秒）：次数与挂钟任一到达即放弃。 */
    @Value("${amz.logistics.return-sync.poll-deadline-ms:90000}")
    private long pollDeadlineMs;

    @Autowired(required = false)
    private SpApiReportsClient spApiReportsClient;

    @Autowired
    private ReturnOrderService returnOrderService;

    /**
     * 拉取并导入某店铺的 FBA 客户退货报表。
     *
     * @param shopId        店铺
     * @param marketplaceId 站点
     * @param windowDays    回溯天数（报表按窗口取数）
     * @return 本次统计（拉取行数 / 新导入 / 跳过 / 行级错误）
     */
    public SyncResult syncShop(Long shopId, String marketplaceId, int windowDays) {
        if (shopId == null || marketplaceId == null || marketplaceId.isBlank()) {
            throw new CodeErrorException("shopId 与 marketplaceId 均不能为空");
        }
        if (spApiReportsClient == null) {
            throw new CodeErrorException("报表取数通道不可用（spapi 客户端未装配）");
        }
        int days = windowDays <= 0 ? 7 : windowDays;
        // 官方 dataStartTime/dataEndTime 要 ISO-8601 带时区；用 Instant（不是 LocalDateTime，
        // 后者没有偏移，ISO_INSTANT 会直接抛 UnsupportedTemporalTypeException）
        java.time.Instant now = java.time.Instant.now();
        String dataEnd = now.toString();
        String dataStart = now.minus(java.time.Duration.ofDays(days)).toString();

        String reportId = requestReport(shopId, marketplaceId, dataStart, dataEnd);
        String content = awaitAndDownload(shopId, reportId);
        CustomerReturnsParser.ParseResult parsed = CustomerReturnsParser.parse(content);

        int imported = 0;
        int skipped = 0;
        for (CustomerReturnsParser.Row row : parsed.rows()) {
            ReturnOrder order = toReturnOrder(shopId, row);
            if (returnOrderService.importAmazonReturn(order)) {
                imported++;
            } else {
                skipped++;
            }
        }
        if (!parsed.errors().isEmpty()) {
            // 行级错误不中断整批（几千行不能因一行全丢），但必须可见
            log.warn("退货报表存在 {} 行级错误 shopId={}，首条：{}",
                    parsed.errors().size(), shopId, parsed.errors().get(0));
        }
        log.info("退货同步完成 shopId={} 窗口={}天 拉取={} 新导入={} 跳过={} 行错误={}",
                shopId, days, parsed.rows().size(), imported, skipped, parsed.errors().size());
        return new SyncResult(parsed.rows().size(), imported, skipped, parsed.errors().size());
    }

    private String requestReport(Long shopId, String marketplaceId, String dataStart, String dataEnd) {
        Result<String> result = spApiReportsClient.requestReport(shopId, marketplaceId,
                CustomerReturnsParser.REPORT_TYPE, dataStart, dataEnd);
        if (result == null || result.getCode() != 200 || result.getData() == null
                || result.getData().isBlank()) {
            throw new CodeErrorException("创建退货报表请求失败："
                    + (result == null ? "无响应" : result.getMessage()));
        }
        return result.getData();
    }

    /**
     * 轮询至终态并下载文档。
     * <p>
     * 双上限：次数与挂钟。任一到达即抛错——超时**不静默返回空报表**，
     * 否则同步会把「没拉到」记成「本期无退货」。
     */
    private String awaitAndDownload(Long shopId, String reportId) {
        long deadline = System.currentTimeMillis() + pollDeadlineMs;
        RemoteReportInfo info = null;
        for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
            Result<RemoteReportInfo> result = spApiReportsClient.getReport(reportId, shopId);
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                throw new CodeErrorException("查询退货报表状态失败："
                        + (result == null ? "无响应" : result.getMessage()));
            }
            info = result.getData();
            if (info.isTerminal()) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            sleep();
        }
        if (info == null || !info.isDone()) {
            String status = info == null ? "未知" : info.getProcessingStatus();
            throw new CodeErrorException("退货报表未在限制内就绪（已尝试 " + maxPollAttempts
                    + " 次 / " + pollDeadlineMs + "ms，最后状态=" + status + "）");
        }
        if (info.getDocumentId() == null || info.getDocumentId().isBlank()) {
            throw new CodeErrorException("退货报表状态为 DONE 但缺少 documentId，无法下载");
        }
        Result<String> doc = spApiReportsClient.downloadDocument(info.getDocumentId(), shopId);
        if (doc == null || doc.getCode() != 200 || doc.getData() == null) {
            throw new CodeErrorException("下载退货报表文档失败："
                    + (doc == null ? "无响应" : doc.getMessage()));
        }
        return doc.getData();
    }

    private void sleep() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeErrorException("退货报表轮询被中断");
        }
    }

    /** 报表行 → 退货单（source=AMAZON_FBA；externalKey 由解析器生成，落库层拼店铺）。 */
    private ReturnOrder toReturnOrder(Long shopId, CustomerReturnsParser.Row row) {
        ReturnOrder order = new ReturnOrder();
        order.setShopId(shopId);
        order.setAmazonOrderId(row.getOrderId());
        order.setSku(row.getSku());
        order.setAsin(row.getAsin());
        order.setQuantity(row.getQuantity());
        order.setSource("AMAZON_FBA");
        order.setDisposition(row.getDisposition());
        order.setReturnReason(row.getReason());
        order.setExternalKey(row.getExternalKey());
        return order;
    }

    /** 同步统计。 */
    public record SyncResult(int fetched, int imported, int skipped, int rowErrors) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("fetched", fetched);
            map.put("imported", imported);
            map.put("skipped", skipped);
            map.put("rowErrors", rowErrors);
            return map;
        }
    }
}
