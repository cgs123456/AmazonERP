package com.amz.client;

import com.amz.client.dto.FinancialEvent;
import com.amz.client.parse.FinancialEventParser;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SP-API Finances API（v0）真实客户端：按入账时间增量拉取财务事件。
 * <p>
 * GET /finances/v0/financialEvents?PostedAfter=...&amp;PostedBefore=...&amp;MaxResults=100，
 * NextToken 自动翻页（上限 {@link #MAX_PAGES} 页，防止异常大窗口拖垮调用方）。
 * <p>
 * 原始事件解析走 {@link FinancialEventParser}（独立静态解析器，单测覆盖
 * 字段路径 / 符号约定 / 幂等键），本类只负责取数与翻页。
 */
@Component
@Profile("!mock")
public class FinancesRealClient implements FinancesClient {

    private static final Logger log = LoggerFactory.getLogger(FinancesRealClient.class);

    private static final String FINANCES_PATH = "/finances/v0/financialEvents";

    /** Finances 端点标识，用于限流指标维度。 */
    private static final String FINANCES_ENDPOINT = "finances";

    /** 翻页上限：单次拉取超过 10 页（约 3200 条）视为异常窗口，截断并告警。 */
    private static final int MAX_PAGES = 10;

    private static final int MAX_RESULTS = 100;

    @Autowired
    private SpApiGateway gateway;

    @Override
    public List<FinancialEvent> listFinancialEvents(Long shopId, String postedAfter, String postedBefore) {
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, null);

        List<FinancialEvent> all = new ArrayList<>();
        String nextToken = null;
        int page = 0;
        while (page < MAX_PAGES) {
            Map<String, String> params = new java.util.HashMap<>();
            if (postedAfter != null && !postedAfter.isBlank()) {
                params.put("PostedAfter", postedAfter);
            }
            if (postedBefore != null && !postedBefore.isBlank()) {
                params.put("PostedBefore", postedBefore);
            }
            params.put("MaxResults", String.valueOf(MAX_RESULTS));
            if (nextToken != null) {
                params.put("NextToken", nextToken);
            }
            JsonObject resp = gateway.callJson("GET", shop, FINANCES_ENDPOINT,
                    FINANCES_PATH, SpApiGateway.canonicalQuery(params), null);

            JsonObject payload = resp.has("payload") && resp.get("payload").isJsonObject()
                    ? resp.getAsJsonObject("payload") : new JsonObject();
            JsonObject financialEvents = payload.has("FinancialEvents")
                    && payload.get("FinancialEvents").isJsonObject()
                    ? payload.getAsJsonObject("FinancialEvents") : null;
            all.addAll(FinancialEventParser.parse(financialEvents));

            nextToken = str(resp, "nextToken");
            page++;
            if (nextToken == null || nextToken.isBlank()) {
                break;
            }
        }
        if (nextToken != null && !nextToken.isBlank()) {
            log.warn("listFinancialEvents truncated at {} pages shopId={} — window too large, "
                    + "caller should narrow the time range", MAX_PAGES, shopId);
        }
        log.info("listFinancialEvents shopId={} postedAfter={} postedBefore={} events={}",
                shopId, postedAfter, postedBefore, all.size());
        return all;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).isJsonObject() ? null : o.get(key).getAsString();
    }
}
