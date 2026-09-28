package com.amz.client;

import com.amz.client.dto.ReportInfo;
import com.amz.client.parse.ReportDocumentDecoder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SP-API Reports API（2021-06-30）真实客户端。
 * <p>
 * 三段式流程：
 * <ol>
 *   <li>POST /reports/2021-06-30/reports —— 创建报表请求</li>
 *   <li>GET /reports/2021-06-30/reports/{reportId} —— 查询 processingStatus</li>
 *   <li>GET /reports/2021-06-30/documents/{documentId} —— 取文档元数据（S3 预签名地址 + 压缩格式），
 *       下载后按元数据解压（结算原表为 TSV + GZIP）</li>
 * </ol>
 * <p>
 * 鉴权 / 限流 / 429 重试统一走 {@link SpApiGateway}。
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效（与 product 模块的
 * ListingsRealClient / ListingsMockClient 切换方式一致）。
 */
@Component
@Profile("!mock")
public class ReportsRealClient implements ReportsClient {

    private static final Logger log = LoggerFactory.getLogger(ReportsRealClient.class);

    private static final String REPORTS_PATH = "/reports/2021-06-30/reports";
    private static final String DOCUMENTS_PATH = "/reports/2021-06-30/documents";

    /**
     * 官方 operationId（限流维度；数值见 contracts/reports_2021-06-30.json 的 Usage Plan 表）。
     * <p>
     * 旧实现三个 operation 共用一个粗粒度 {@code "reports"} 桶（兜底 1 req/s、burst 30）：
     * 对 0.0167 req/s 的 {@code createReport} 越权约 60 倍。
     */
    private static final String OP_CREATE_REPORT = "reports.createReport";
    private static final String OP_GET_REPORT = "reports.getReport";
    private static final String OP_GET_REPORT_DOCUMENT = "reports.getReportDocument";

    /**
     * 统一 SP-API 网关（构造器注入：缺少该 Bean 时启动即失败，而不是首次调用时才 NPE）。
     */
    private final SpApiGateway gateway;

    public ReportsRealClient(SpApiGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String createReport(Long shopId, String marketplaceId, String reportType,
                               String dataStartTime, String dataEndTime) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);

        JsonObject body = new JsonObject();
        body.addProperty("reportType", reportType);
        JsonArray mks = new JsonArray();
        mks.add(marketplaceId);
        body.add("marketplaceIds", mks);
        if (dataStartTime != null) {
            body.addProperty("dataStartTime", dataStartTime);
        }
        if (dataEndTime != null) {
            body.addProperty("dataEndTime", dataEndTime);
        }

        JsonObject resp = gateway.callJson("POST", shop, OP_CREATE_REPORT, REPORTS_PATH,
                null, body.toString());
        String reportId = str(resp, "reportId");
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalStateException("createReport response missing required reportId");
        }
        log.info("createReport shopId={} reportType={} reportId={}", shopId, reportType, reportId);
        return reportId;
    }

    @Override
    public ReportInfo getReport(Long shopId, String reportId) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, null);
        JsonObject resp = gateway.callJson("GET", shop, OP_GET_REPORT,
                REPORTS_PATH + "/" + reportId, null, null);

        ReportInfo info = new ReportInfo();
        info.setReportId(required(resp, "reportId"));
        info.setReportType(required(resp, "reportType"));
        info.setProcessingStatus(required(resp, "processingStatus"));
        info.setCreatedTime(required(resp, "createdTime"));
        info.setDocumentId(str(resp, "reportDocumentId"));
        return info;
    }

    @Override
    public String downloadDocument(Long shopId, String documentId) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, null);
        // 注意：只对取文档元数据这一次 SP-API 调用限流；随后的 S3 预签名下载
        // （gateway.downloadBytes）不是 SP-API 调用，不消耗令牌。
        JsonObject meta = gateway.callJson("GET", shop, OP_GET_REPORT_DOCUMENT,
                DOCUMENTS_PATH + "/" + documentId, null, null);
        String url = str(meta, "url");
        String compression = str(meta, "compressionAlgorithm");
        if (url == null || url.isBlank()) {
            throw new RuntimeException("Report document has no download url, documentId=" + documentId);
        }
        byte[] raw = gateway.downloadBytes(url);
        try {
            String content = ReportDocumentDecoder.decode(raw, compression);
            log.info("downloadDocument shopId={} documentId={} compression={} bytes={} chars={}",
                    shopId, documentId, compression, raw.length, content.length());
            return content;
        } catch (Exception e) {
            throw new RuntimeException("Report document decode failed documentId=" + documentId
                    + " compression=" + compression + ": " + e.getMessage(), e);
        }
    }

    private static String required(JsonObject o, String key) {
        String value = str(o, key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("getReport response missing required " + key);
        }
        return value;
    }
    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).isJsonObject() ? null : o.get(key).getAsString();
    }
}
