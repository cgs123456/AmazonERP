package com.amz.agent;

import com.amz.client.AdServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.amz.agent.ErpToolSupport.fail;
import static com.amz.agent.ErpToolSupport.isSuccess;
import static com.amz.agent.ErpToolSupport.ok;
import static com.amz.agent.ErpToolSupport.toDouble;
import static com.amz.agent.ErpToolSupport.toInt;
import static com.amz.agent.ErpToolSupport.toLong;
import static com.amz.agent.ErpToolSupport.toStr;

/**
 * 广告域 Agent 工具（从 ErpToolExecutor 拆出，§S32）。
 * <p>
 * <b>分组依据是依赖边界</b>：本族只依赖 AdServiceClient，与其它族零互调用
 * （拆分前逐方法核对）。广告域工具（表现分析/竞品监控/投放查询/搜索词/优化）。
 * <p>
 * 工具名与返回结构逐字保留，ErpToolExecutor 的分发改为委托调用。
 */
@Slf4j
public class ErpAdTools {

    @Autowired(required = false)
    private AdServiceClient adServiceClient;

    /**
     * 工具 8：广告 ACoS/ROAS 分析。
     * 通过 Feign 调用 amz-service-ad 的 /ad/reports 端点。
     */
    public String analyzeAdPerformance(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        Integer days = toInt(args.get("days"), 7);

        log.info("工具调用 analyze_ad_performance shopId={} asin={} days={}", shopId, asin, days);
        if (adServiceClient == null) {
            return fail("广告服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = adServiceClient.getReports(shopId);
            if (!isSuccess(result)) {
                return fail("广告报表查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> reports = result.getData();
            if (reports == null || reports.isEmpty()) {
                return ok("暂无广告报表数据", Map.of("reports", List.of()));
            }
            // 汇总 ACoS/ROAS
            double totalSpend = 0, totalSales = 0;
            for (Map<String, Object> r : reports) {
                totalSpend += toDouble(r.get("cost"));
                totalSales += toDouble(r.get("sales"));
            }
            double acos = totalSales > 0 ? totalSpend / totalSales : 0;
            double roas = totalSpend > 0 ? totalSales / totalSpend : 0;
            String asinDesc = asin != null ? "ASIN " + asin + " " : "";
            return ok(String.format("%s近 %d 天广告表现：花费 $%.2f，广告销售额 $%.2f，ACOS=%.1f%%，ROAS=%.2f",
                            asinDesc, days, totalSpend, totalSales, acos * 100, roas),
                    Map.of("adSpend", totalSpend, "adSales", totalSales,
                            "acos", acos, "roas", roas, "days", days, "reports", reports));
        } catch (Exception e) {
            log.error("analyze_ad_performance Feign 调用失败 shopId={}", shopId, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 9：竞品价格监控。
     * 通过 Feign 调用 amz-service-ad 的 /ad/competitor 端点。
     */
    public String monitorCompetitorPrice(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));

        log.info("工具调用 monitor_competitor_price shopId={} asin={}", shopId, asin);
        if (adServiceClient == null) {
            return fail("广告服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = adServiceClient.getCompetitor(shopId, asin);
            if (!isSuccess(result)) {
                return fail("竞品监控查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            Object myPrice = data.get("myPrice");
            Object buyBox = data.get("buyBoxPrice");
            String suggestion = (myPrice != null && buyBox != null)
                    ? "建议参考 Buy Box 价格调整定价"
                    : "竞品价格数据暂未配置（需接入 SP-API Pricing API）";
            return ok(String.format("ASIN %s 竞品监控：%s", asin, suggestion), data);
        } catch (Exception e) {
            log.error("monitor_competitor_price Feign 调用失败 shopId={}", shopId, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 17：查询广告汇总。
     */
    public String queryAdvertising(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        Integer days = toInt(args.get("days"), 7);
        log.info("工具调用 query_advertising shopId={} days={}", shopId, days);
        if (adServiceClient == null) return fail("广告服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = adServiceClient.getReports(shopId);
            double totalSpend = 0, totalSales = 0;
            if (result != null && result.getData() != null) {
                for (Map<String, Object> r : result.getData()) {
                    totalSpend += toDouble(r.get("cost"));
                    totalSales += toDouble(r.get("sales"));
                }
            }
            double acos = totalSales > 0 ? totalSpend / totalSales : 0;
            return ok(String.format("广告汇总（近%d天）：花费$%.2f，销售额$%.2f，ACoS=%.1f%%", days, totalSpend, totalSales, acos * 100),
                    Map.of("adSpend", totalSpend, "adSales", totalSales, "acos", acos, "days", days));
        } catch (Exception e) {
            log.error("query_advertising 调用广告服务失败 shopId={} days={}", shopId, days, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 20：搜索词分析（真实数据，来自 amz-service-ad 的搜索词综合分析）。
     */
    public String analyzeSearchTerms(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        Integer days = toInt(args.get("days"), 7);
        log.info("工具调用 analyze_search_terms shopId={} asin={} days={}", shopId, asin, days);
        if (adServiceClient == null) return fail("广告服务客户端未启用");
        try {
            Result<Map<String, Object>> result = adServiceClient.analyzeSearchTerms(shopId, null, days);
            if (!isSuccess(result)) {
                return fail("搜索词分析失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            int converting = toInt(data.get("convertingTerms"), 0);
            int waste = toInt(data.get("wasteTerms"), 0);
            Object overallAcos = data.get("overallAcos");
            return ok(String.format("搜索词分析（近%d天）：出单词 %d 个，浪费词 %d 个，整体 ACoS %s（真实数据）",
                            days, converting, waste, overallAcos != null ? overallAcos : "0"), data);
        } catch (Exception e) {
            log.error("analyze_search_terms Feign 调用失败 shopId={}", shopId, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 23：广告优化建议（当前 ACoS 来自真实广告报表，建议为基于阈值的规则推导）。
     */
    @ToolPermission("agent:operate")
    public String optimizeAdCampaign(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String campaignId = toStr(args.get("campaignId"));
        Integer days = toInt(args.get("days"), 7);
        log.info("工具调用 optimize_ad_campaign shopId={} campaignId={} days={}", shopId, campaignId, days);
        if (adServiceClient == null) return fail("广告服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = adServiceClient.getReports(shopId);
            if (!isSuccess(result)) {
                return fail("广告报表查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> reports = result.getData();
            if (reports == null || reports.isEmpty()) {
                return ok("暂无广告报表数据", Map.of("shopId", shopId, "campaigns", List.of()));
            }
            List<Map<String, Object>> campaignAnalysis = new ArrayList<>();
            for (Map<String, Object> r : reports) {
                double cost = toDouble(r.get("cost"));
                double sales = toDouble(r.get("sales"));
                double acos = sales > 0 ? cost / sales : 0;
                String cid = toStr(r.get("campaignId"));
                if (campaignId != null && !campaignId.equals(cid)) continue;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("campaignId", cid);
                a.put("currentAcos", String.format("%.1f%%", acos * 100));
                List<String> sugg = new ArrayList<>();
                if (acos > 0.4) sugg.add("ACoS 过高(>40%)，建议暂停高花费低转化关键词");
                else if (acos < 0.15 && sales > 0) sugg.add("ACoS 健康(<15%)，建议适度提高出价扩量");
                else sugg.add("ACoS 正常，维持观察");
                a.put("suggestions", sugg);
                campaignAnalysis.add(a);
            }
            if (campaignAnalysis.isEmpty()) {
                return ok("未匹配到 campaignId=" + campaignId + " 的广告活动",
                        Map.of("shopId", shopId, "campaignId", campaignId));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("shopId", shopId);
            out.put("days", days);
            out.put("campaigns", campaignAnalysis);
            out.put("methodNote", "当前 ACoS 由真实广告报表(cost/sales)计算；优化建议为基于阈值的规则建议，非关键词级精细调优");
            return ok(String.format("广告优化建议：覆盖 %d 个广告活动（真实报表数据，建议为规则推导）",
                            campaignAnalysis.size()), out);
        } catch (Exception e) {
            log.error("optimize_ad_campaign Feign 调用失败 shopId={}", shopId, e);
            return fail("广告服务暂时不可用: " + e.getMessage());
        }
    }
}
