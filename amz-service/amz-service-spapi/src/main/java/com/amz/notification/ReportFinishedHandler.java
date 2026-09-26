package com.amz.notification;

import com.amz.client.ReportsRealClient;
import com.amz.client.dto.ReportInfo;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * REPORT_PROCESSING_FINISHED 处理器：报表生成完成后核对状态并下载结果文档。
 * <p>
 * 与 feed 同理，只有 DONE 才下载；FATAL / CANCELLED 只记录不重试——
 * 报表在 Amazon 侧已经失败，重试我们的处理逻辑不会让它变成功。
 * <p>
 * 边界声明：本 Handler 只做「下载并核对字节数」，不做报表内容解析入库。
 * 报表解析要对接具体 reportType 的表结构，属于独立需求，不在此链路硬塞。
 */
@Slf4j
@Component
public class ReportFinishedHandler implements NotificationEventHandler {

    /** 支持的通知类型。 */
    public static final String TYPE = "REPORT_PROCESSING_FINISHED";

    /** Payload 内的业务节点名。 */
    public static final String PAYLOAD_NODE = "ReportProcessingFinishedNotification";

    /** 缺少业务节点。 */
    public static final String ERR_MISSING_NODE = "MISSING_REPORT_NODE";
    /** 缺少 reportId。 */
    public static final String ERR_MISSING_REPORT_ID = "MISSING_REPORT_ID";
    /** 下载失败（通常可重试）。 */
    public static final String ERR_DOWNLOAD_FAILED = "REPORT_DOWNLOAD_FAILED";

    private final ReportsRealClient reportsClient;

    public ReportFinishedHandler(ReportsRealClient reportsClient) {
        this.reportsClient = reportsClient;
    }

    @Override
    public boolean supports(String notificationType) {
        return TYPE.equalsIgnoreCase(notificationType);
    }

    @Override
    public void handle(NotificationContext ctx) {
        JsonNode node = ctx.payload() == null ? null : ctx.payload().get(PAYLOAD_NODE);
        if (node == null || !node.isObject()) {
            throw NotificationProcessingException.fatal(ERR_MISSING_NODE,
                    "REPORT_PROCESSING_FINISHED 缺少 " + PAYLOAD_NODE + " 节点");
        }
        String reportId = text(node, "reportId");
        if (reportId == null) {
            throw NotificationProcessingException.fatal(ERR_MISSING_REPORT_ID, "通知缺少 reportId");
        }
        String status = text(node, "processingStatus");
        if (!"DONE".equalsIgnoreCase(status)) {
            log.info("[ReportFinishedHandler] 报表未终态，跳过下载：reportId={}, processingStatus={}",
                    reportId, status);
            return;
        }
        if (ctx.shopId() == null) {
            throw NotificationProcessingException.terminal(
                    NotificationInboxStatus.UNRESOLVED_SUBSCRIPTION, "UNRESOLVED_SHOP",
                    "报表事件未归属店铺，补订阅映射后重放");
        }
        try {
            ReportInfo info = reportsClient.getReport(ctx.shopId(), reportId);
            if (info == null || info.getDocumentId() == null) {
                log.warn("[ReportFinishedHandler] 报表已 DONE 但无结果文档：reportId={}", reportId);
                return;
            }
            String document = reportsClient.downloadDocument(ctx.shopId(), info.getDocumentId());
            log.info("[ReportFinishedHandler] 报表文档已下载：reportId={}, documentId={}, bytes={}",
                    reportId, info.getDocumentId(), document == null ? 0 : document.length());
        } catch (NotificationProcessingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw NotificationProcessingException.retryable(ERR_DOWNLOAD_FAILED,
                    "下载报表文档失败：" + e.getClass().getSimpleName());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }
}