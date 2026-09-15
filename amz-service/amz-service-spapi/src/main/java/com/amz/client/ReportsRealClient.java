package com.amz.client;

import com.amz.client.dto.ReportInfo;
import com.amz.client.parse.ReportDocumentDecoder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SP-API Reports API（2021-09-01）真实客户端。
 * <p>
 * 三段式流程：
 * <ol>
 *   <li>POST /reports/2021-09-01/reports —— 创建报表请求</li>
 *   <li>GET /reports/2021-09-01/reports/{reportId} —— 查询 processingStatus</li>
 *   <li>GET /reports/2021-09-01/documents/{documentId} —— 取文档元数据（S3 预签名地址 + 压缩格式），
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

    private static final String REPORTS_PATH = "/reports/2021-09-01/reports";
    private static final String DOCUMENTS_PATH = "/reports/2021-09-01/documents";

    /** Reports 端点标识，用于限流指标维度。 */
    private static final String REPORTS_ENDPOINT = "reports";

    @Autowired
    private SpApiGateway gateway;

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

        JsonObject resp = gateway.callJson("POST", shop, REPORTS_ENDPOINT, REPORTS_PATH,
                null, body.toString());
        String reportId = resp.has("reportId") ? resp.get("reportId").getAsString() : null;
        log.info("createReport shopId={} reportType={} reportId={}", shopId, reportType, reportId);
        return reportId;
    }

    @Override
    public ReportInfo getReport(Long shopId, String reportId) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, null);
        JsonObject resp = gateway.callJson("GET", shop, REPORTS_ENDPOINT,
                REPORTS_PATH + "/" + reportId, null, null);

        ReportInfo info = new ReportInfo();
        info.setReportId(str(resp, "reportId"));
        info.setReportType(str(resp, "reportType"));
        info.setProcessingStatus(str(resp, "processingStatus"));
        info.setDocumentId(str(resp, "resultDocumentId"));
        return info;
    }

    @Override
    public String downloadDocument(Long shopId, String documentId) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, null);
        JsonObject meta = gateway.callJson("GET", shop, REPORTS_ENDPOINT,
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

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).isJsonObject() ? null : o.get(key).getAsString();
    }
}
