package com.amz.agent;

import com.amz.agent.review.ReviewAnalysisResult;
import com.amz.agent.review.ReviewInfo;
import com.amz.agent.selection.SelectionAnalysisResult;
import com.amz.agent.selection.SelectionOpportunityInput;
import com.amz.client.ReportServiceClient;
import com.amz.ai.knowledge.KnowledgeChunk;
import com.amz.result.Result;
import com.amz.ai.knowledge.KnowledgeSearchService;
import com.amz.agent.review.ReviewAnalysisService;
import com.amz.agent.selection.SelectionAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
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
 * 本地分析类 Agent 工具（从 ErpToolExecutor 拆出，§S32）。
 * <p>
 * <b>这一族的共性是不走 Feign 跨服务</b>（除 analyzeSalesTrend 用 report 客户端）：
 * 评论洞察、选品分析、知识库检索都落在 amz-service-ai 自己的领域服务上。
 * 与按客户端切的几个族并列，是「依赖边界」分组的另一半——本地能力归一处。
 * <p>
 * 工具名与回结构逐字保留，分发改为委托调用。
 */
@Slf4j
@Component
public class ErpAnalysisTools {

    /**
     * 自动草稿标识：模板/LLM 生成的文本草稿（非业务数据缺口），与 [演示估算] 区分，避免被误读为真实分析结果。
     */
    private static final String AUTO_DRAFT_NOTE = "[自动草稿·模板生成·非业务数据·需人工复核] ";

    private final Gson gson = new Gson();

    @Autowired
    private ReviewAnalysisService reviewAnalysisService;

    @Autowired
    private SelectionAnalysisService selectionAnalysisService;

    @Autowired
    private KnowledgeSearchService knowledgeSearchService;

    @Autowired(required = false)
    private ReportServiceClient reportServiceClient;

    /**

     * 工具 21：销售趋势深度分析（真实数据，来自 amz-service-report 的日销售聚合）。

     */

    public String analyzeSalesTrend(Map<String, Object> args) {

        Long shopId = toLong(args.get("shopId"));

        Integer days = toInt(args.get("days"), 30);

        log.info("工具调用 analyze_sales_trend shopId={} days={}", shopId, days);

        if (reportServiceClient == null) return fail("报表服务客户端未启用");

        try {

            Result<List<Map<String, Object>>> result = reportServiceClient.getSalesTrend(shopId, days);

            if (!isSuccess(result)) {

                return fail("销售趋势查询失败: " + (result != null ? result.getMessage() : "无响应"));

            }

            List<Map<String, Object>> trend = result.getData();

            if (trend == null || trend.isEmpty()) {

                return ok("暂无销售趋势数据（近" + days + "天无记录）", Map.of("days", days, "points", 0));

            }

            List<Double> values = trend.stream().map(m -> toDouble(m.get("value"))).toList();

            double first = values.get(0);

            double last = values.get(values.size() - 1);

            double total = values.stream().mapToDouble(Double::doubleValue).sum();

            String direction = last > first ? "UP" : (last < first ? "DOWN" : "FLAT");

            double growth = first > 0 ? (last - first) / first * 100 : 0;

            Map<String, Object> peak = trend.get(0);

            for (Map<String, Object> p : trend) {

                if (toDouble(p.get("value")) > toDouble(peak.get("value"))) peak = p;

            }

            Map<String, Object> out = new LinkedHashMap<>();

            out.put("shopId", shopId);

            out.put("days", days);

            out.put("points", trend.size());

            out.put("direction", direction);

            out.put("growthRateFirstToLast", String.format("%.1f%%", growth));

            out.put("totalSales", total);

            out.put("peakDay", peak.get("day"));

            out.put("peakValue", peak.get("value"));

            out.put("series", trend);

            return ok(String.format("近%d天销售趋势：%s，首尾环比 %s，峰值 %s（真实数据）",

                            days, direction, out.get("growthRateFirstToLast"), peak.get("day")), out);

        } catch (Exception e) {

            log.error("analyze_sales_trend Feign 调用失败 shopId={}", shopId, e);

            return fail("报表服务暂时不可用: " + e.getMessage());

        }

    }


    /**

     * 工具 28：AI 自动生成回复。

     */

    @ToolPermission("agent:operate")

    public String autoReplyMessage(Map<String, Object> args) {

        Long shopId = toLong(args.get("shopId"));

        String messageId = toStr(args.get("messageId"));

        log.info("工具调用 auto_reply_message shopId={} messageId={}", shopId, messageId);

        String subject = toStr(args.get("subject"));

        if (subject == null) subject = "Customer Inquiry";

        String draftReply;

        if (subject.contains("Return") || subject.contains("退货")) {

            draftReply = "Dear customer, we're sorry to hear about your issue. " +

                    "Please provide your order ID and we'll process a full refund or replacement immediately.";

        } else if (subject.contains("Shipping") || subject.contains("物流")) {

            draftReply = "Thank you for your inquiry. Your order has been shipped and the tracking number is " +

                    "TRK" + System.currentTimeMillis() % 100000 + ". Estimated delivery is 3-5 business days.";

        } else {

            draftReply = "Thank you for contacting us. We've received your message and will respond within 24 hours.";

        }

        Map<String, Object> reply = new LinkedHashMap<>();

        reply.put("messageId", messageId);

        reply.put("draftReply", draftReply);

        return ok(AUTO_DRAFT_NOTE + "已生成回复草稿（模板生成，非业务数据，需人工复核后发送）", reply);

    }


    /**

     * 工具 29：检索店铺知识库（只读）。

     * <p>

     * shopId 越权校验由 execute 入口统一完成；query 为空直接 fail；

     * 检索失败降级为 fail，不抛异常阻断 Agent 主流程。

     */

    public String queryKnowledgeBase(Map<String, Object> args) {

        Long shopId = toLong(args.get("shopId"));

        String query = toStr(args.get("query"));

        int topN = toInt(args.get("topN"), 5);

        log.info("工具调用 query_knowledge_base shopId={} topN={}", shopId, topN);

        if (query == null || query.isBlank()) {

            return fail("检索问题不能为空");

        }

        if (knowledgeSearchService == null) {

            return fail("知识库服务未启用");

        }

        try {

            List<KnowledgeChunk> chunks = knowledgeSearchService.search(shopId, query.trim(), topN);

            if (chunks == null || chunks.isEmpty()) {

                return ok("知识库暂无相关内容", List.of());

            }

            List<Map<String, Object>> items = new ArrayList<>(chunks.size());

            for (KnowledgeChunk chunk : chunks) {

                if (chunk == null) {

                    continue;

                }

                Map<String, Object> item = new LinkedHashMap<>();

                item.put("docId", chunk.getDocId());

                item.put("filename", chunk.getFilename());

                item.put("chunkIndex", chunk.getChunkIndex());

                item.put("content", chunk.getContent());

                item.put("score", chunk.getScore());

                items.add(item);

            }

            Map<String, Object> data = new LinkedHashMap<>();

            data.put("query", query.trim());

            data.put("count", items.size());

            data.put("chunks", items);

            return ok(String.format("知识库命中 %d 条相关内容", items.size()), data);

        } catch (Exception e) {

            log.error("query_knowledge_base 检索失败 shopId={}", shopId, e);

            return fail("知识库检索暂时不可用");

        }

    }


    /**

     * 工具 13：商品评论分析。

     * 联动 ReviewAnalysisService，调用 DeepSeek LLM 进行情感分析、痛点聚类和改进建议生成。

     */

    public String analyzeProductReviews(Map<String, Object> args) {

        Long shopId = toLong(args.get("shopId"));

        String asin = toStr(args.get("asin"));

        String reviewsJson = toStr(args.get("reviewsJson"));



        log.info("工具调用 analyze_product_reviews shopId={} asin={} reviewsJsonLen={}",

                shopId, asin, reviewsJson == null ? 0 : reviewsJson.length());



        if (reviewsJson == null || reviewsJson.isBlank()) {

            return fail("评论列表 JSON 不能为空");

        }



        try {

            // 去除可能的 markdown 代码块标记

            String json = reviewsJson.trim();

            if (json.startsWith("```")) {

                json = json.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");

            }

            List<ReviewInfo> reviews = gson.fromJson(json, new TypeToken<List<ReviewInfo>>(){}.getType());

            if (reviews == null || reviews.isEmpty()) {

                return fail("评论列表解析后为空");

            }

            ReviewAnalysisResult result = reviewAnalysisService.analyze(reviews);

            return ok(String.format("ASIN %s 评论分析完成：情感得分 %.0f，痛点 %d 个，建议 %d 条",

                            asin,

                            result.getSentimentScore() != null ? result.getSentimentScore() : 0,

                            result.getPainPoints() != null ? result.getPainPoints().size() : 0,

                            result.getSuggestions() != null ? result.getSuggestions().size() : 0),

                    gson.toJsonTree(result));

        } catch (Exception e) {

            log.error("评论分析失败", e);

            return fail("评论分析失败: " + e.getMessage());

        }

    }



    /**

     * 工具 14：AI 选品分析。

     * 联动 SelectionAnalysisService，调用 DeepSeek LLM 生成市场分析、

     * 竞争评估、趋势判断和是否进入市场的选品建议。

     *

     * 入参字段：keyword（关键词）、marketplace（站点，默认 US），

     * 可选携带已分析的机会数据：asin/title/category/avgPrice/avgReviews/avgRating/

     * searchVolume/competitorCount/reviewBarrier/opportunityScore/trend30d/trend90d。

     */

    public String analyzeProductSelection(Map<String, Object> args) {

        String keyword = toStr(args.get("keyword"));

        String marketplace = toStr(args.get("marketplace"));

        if (marketplace == null) marketplace = "US";



        log.info("工具调用 analyze_product_selection keyword={} marketplace={}", keyword, marketplace);



        if (keyword == null || keyword.isBlank()) {

            return fail("关键词 keyword 不能为空");

        }



        // 仅透传 LLM 通过其它真实工具已获取的市场/竞争指标；未提供的字段保持 null，

        // 由 SelectionAnalysisService 以「未知」兜底。严禁凭空伪造 avgPrice / searchVolume /

        // opportunityScore 等市场数据（原实现用 ThreadLocalRandom.setSeed 既会抛异常，

        // 又会编造虚假指标，已移除）。

        SelectionOpportunityInput input = new SelectionOpportunityInput();

        input.setAsin(toStr(args.get("asin")));

        input.setTitle(toStr(args.get("title")));

        input.setCategory(toStr(args.get("category")));

        input.setMarketplace(marketplace);

        input.setAvgPrice(args.get("avgPrice") != null ? new BigDecimal(toStr(args.get("avgPrice"))) : null);

        input.setAvgReviews(args.get("avgReviews") != null ? toInt(args.get("avgReviews"), 0) : null);

        input.setAvgRating(args.get("avgRating") != null ? new BigDecimal(toStr(args.get("avgRating"))) : null);

        input.setSearchVolume(args.get("searchVolume") != null ? toInt(args.get("searchVolume"), 0) : null);

        input.setCompetitorCount(args.get("competitorCount") != null ? toInt(args.get("competitorCount"), 0) : null);

        input.setReviewBarrier(toStr(args.get("reviewBarrier")));

        input.setOpportunityScore(args.get("opportunityScore") != null ? new BigDecimal(toStr(args.get("opportunityScore"))) : null);

        input.setTrend30d(toStr(args.get("trend30d")));

        input.setTrend90d(toStr(args.get("trend90d")));



        try {

            SelectionAnalysisResult result = selectionAnalysisService.analyzeOpportunity(input);

            Map<String, Object> data = new HashMap<>();

            data.put("aiSummary", result.getAiSummary());

            data.put("aiSuggestion", result.getAiSuggestion());

            data.put("input", input);

            return ok(String.format("关键词「%s」（%s）AI 选品分析完成：%s",

                            keyword, marketplace,

                            result.getAiSummary() != null ? result.getAiSummary() : "无摘要"),

                    data);

        } catch (Exception e) {

            log.error("AI 选品分析失败 keyword={}", keyword, e);

            return fail("AI 选品分析失败: " + e.getMessage());

        }

    }
}
