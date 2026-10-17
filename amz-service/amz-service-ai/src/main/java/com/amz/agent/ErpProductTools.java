package com.amz.agent;

import com.amz.client.ProductServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
import java.util.HashMap;
import static com.amz.agent.ErpToolSupport.inferLanguage;
import static com.amz.agent.ErpToolSupport.toBool;

/**
 * 商品域 Agent 工具（从 ErpToolExecutor 拆出，§S32）。
 * <p>
 * <b>分组依据是依赖边界</b>：本族只依赖 ProductServiceClient，与其它族零互调用
 * （拆分前逐方法核对）。商品域工具（跨站复制/费用估算/翻译/健康度/SEO 优化）。
 * <p>
 * 工具名与返回结构逐字保留，ErpToolExecutor 的分发改为委托调用。
 */
@Slf4j
@Component
public class ErpProductTools {

    @Autowired(required = false)
    private ProductServiceClient productServiceClient;

    /**
     * 工具 7：跨站点 Listing 复制（P0-2 输出）。
     * 通过 Feign 调用 amz-service-product 的 /product/listing/copy 端点。
     */
    @ToolPermission("agent:operate")
    public String crossMarketplaceListing(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sourceAsin = toStr(args.get("sourceAsin"));
        String targetMarketplace = toStr(args.get("targetMarketplace"));

        log.info("工具调用 cross_marketplace_listing shopId={} asin={} target={}",
                shopId, sourceAsin, targetMarketplace);
        if (productServiceClient == null) {
            return fail("商品服务客户端未启用");
        }
        if (sourceAsin == null || sourceAsin.isBlank()) {
            return fail("sourceAsin 不能为空");
        }
        // sourceAsin 使用独立字段；商品服务负责解析为真实卖家 SKU。
        String sourceMarketplaceId = toStr(args.get("sourceMarketplaceId"));
        if (sourceMarketplaceId == null) sourceMarketplaceId = "ATVPDKIKX0DER"; // 默认 US
        String targetLanguage = toStr(args.get("targetLanguage"));
        if (targetLanguage == null) targetLanguage = inferLanguage(targetMarketplace);
        Object priceMarkupObj = args.get("priceMarkup");

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("shopId", shopId);
        reqBody.put("asin", sourceAsin);
        reqBody.put("sourceMarketplaceId", sourceMarketplaceId);
        reqBody.put("targetMarketplaceId", targetMarketplace);
        reqBody.put("targetLanguage", targetLanguage);
        reqBody.put("priceMarkup", priceMarkupObj != null ? priceMarkupObj : 0.20);

        try {
            Result<Map<String, Object>> result = productServiceClient.copyListing(reqBody);
            if (!isSuccess(result)) {
                return fail("跨站点复制任务创建失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            return ok(String.format("已创建跨站点复制任务：ASIN %s → %s，任务 ID: %s，状态: %s",
                            sourceAsin, targetMarketplace, data.get("taskId"), data.get("status")), data);
        } catch (Exception e) {
            log.error("cross_marketplace_listing Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 10：FBA 费用预估。
     * 通过 Feign 调用 amz-service-product 的 /product/fees/estimate 端点。
     */
    public String estimateFbaFees(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String sku = toStr(args.get("sku"));
        Object weightObj = args.get("weight");
        double weight = weightObj instanceof Number ? ((Number) weightObj).doubleValue() : 0.5;
        String sizeTier = toStr(args.get("sizeTier"));
        if (sizeTier == null) sizeTier = "standard";

        log.info("工具调用 estimate_fba_fees shopId={} sku={} weight={} sizeTier={}",
                shopId, sku, weight, sizeTier);
        if (productServiceClient == null) {
            return fail("商品服务客户端未启用");
        }
        try {
            Result<Map<String, Object>> result = productServiceClient.estimateFbaFees(null, null, weight, sizeTier);
            if (!isSuccess(result)) {
                return fail("FBA 费用估算失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            // §S33.25：降级值必须在**给 LLM 的那句话**里说出来，不能只藏在 data.source。
            // 模型只会把 message 讲给运营听；data 里的 estimated 标记它看不见，
            // 于是「费率表没配、按 3.22/5.45 估算」会被复述成「FBA 履约费 3.22」。
            String source = data.get("source") == null ? "" : String.valueOf(data.get("source"));
            String caveat = "real".equals(source)
                    ? ""
                    : "【注意：该值来自内置估算（费率表未命中或订单服务不可达，source=" + source
                            + "），不是平台实际扣费】";
            return ok(String.format("SKU %s FBA 费用预估（%s, %.2f kg）：履约费 %s + 仓储费 %s = %s%s",
                            sku, sizeTier, weight,
                            data.get("fulfillmentFee"), data.get("storageFee"), data.get("totalFbaFee"),
                            caveat), data);
        } catch (Exception e) {
            log.error("estimate_fba_fees Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 11：多语种翻译。
     * 通过 Feign 调用 amz-service-product 的 /product/translate 端点。
     */
    public String translateListing(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String text = toStr(args.get("text"));
        String sourceLang = toStr(args.get("sourceLang"));
        String targetLang = toStr(args.get("targetLang"));
        if (sourceLang == null) sourceLang = "en";
        if (targetLang == null) targetLang = "de";

        log.info("工具调用 translate_listing shopId={} source={} target={} textLen={}",
                shopId, sourceLang, targetLang, text == null ? 0 : text.length());
        if (productServiceClient == null) {
            return fail("商品服务客户端未启用");
        }
        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("text", text);
        reqBody.put("sourceLang", sourceLang);
        reqBody.put("targetLang", targetLang);

        try {
            Result<Map<String, Object>> result = productServiceClient.translate(reqBody);
            if (!isSuccess(result)) {
                return fail("翻译失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            Map<String, Object> data = result.getData();
            return ok(String.format("已将 %s 文本翻译为 %s", sourceLang, targetLang), data);
        } catch (Exception e) {
            log.error("translate_listing Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 19：Listing 健康度分析（真实数据，来自 amz-service-product 的 Listing 监控）。
     */
    public String analyzeListingHealth(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        log.info("工具调用 analyze_listing_health shopId={} asin={}", shopId, asin);
        if (productServiceClient == null) {
            return fail("商品服务客户端未启用");
        }
        try {
            Result<List<Map<String, Object>>> result = productServiceClient.getListingHealthList(shopId);
            if (!isSuccess(result)) {
                return fail("Listing 健康度查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            Map<String, Object> match = (list == null) ? null : list.stream()
                    .filter(m -> asin != null && asin.equals(toStr(m.get("asin"))))
                    .findFirst().orElse(null);
            if (match == null) {
                return ok(String.format("未查询到 ASIN %s 的 Listing 健康度记录（shopId=%s 共 %d 条）",
                                asin, shopId, list == null ? 0 : list.size()),
                        Map.of("asin", asin == null ? "" : asin, "found", false,
                                "totalRecords", list == null ? 0 : list.size()));
            }
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("asin", match.get("asin"));
            health.put("sku", match.get("sku"));
            health.put("healthScore", match.get("healthScore"));
            health.put("severity", match.get("severity"));
            health.put("titleOk", match.get("titleOk"));
            health.put("bulletPointsOk", match.get("bulletPointsOk"));
            health.put("descriptionOk", match.get("descriptionOk"));
            health.put("imagesOk", match.get("imagesOk"));
            health.put("searchTermsOk", match.get("searchTermsOk"));
            health.put("aplusOk", match.get("aplusOk"));
            health.put("status", match.get("status"));
            List<String> issues = new ArrayList<>();
            if (Boolean.FALSE.equals(toBool(match.get("titleOk")))) issues.add("标题待优化");
            if (Boolean.FALSE.equals(toBool(match.get("bulletPointsOk")))) issues.add("五点描述待优化");
            if (Boolean.FALSE.equals(toBool(match.get("imagesOk")))) issues.add("图片数量不足");
            if (Boolean.FALSE.equals(toBool(match.get("searchTermsOk")))) issues.add("后台搜索词缺失");
            if (Boolean.FALSE.equals(toBool(match.get("aplusOk")))) issues.add("A+ 页面缺失");
            health.put("issues", issues);
            return ok(String.format("ASIN %s Listing 健康度：评分 %s，严重度 %s，问题项 %d 个（真实数据）",
                            asin, match.get("healthScore"), match.get("severity"), issues.size()), health);
        } catch (Exception e) {
            log.error("analyze_listing_health Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }


    /**
     * 工具 24：Listing SEO 优化（建议由真实 Listing 健康度字段推导）。
     */
    @ToolPermission("agent:operate")
    public String optimizeListingSeo(Map<String, Object> args) {
        Long shopId = toLong(args.get("shopId"));
        String asin = toStr(args.get("asin"));
        log.info("工具调用 optimize_listing_seo shopId={} asin={}", shopId, asin);
        if (productServiceClient == null) return fail("商品服务客户端未启用");
        try {
            Result<List<Map<String, Object>>> result = productServiceClient.getListingHealthList(shopId);
            if (!isSuccess(result)) {
                return fail("Listing 健康度查询失败: " + (result != null ? result.getMessage() : "无响应"));
            }
            List<Map<String, Object>> list = result.getData();
            Map<String, Object> match = (list == null) ? null : list.stream()
                    .filter(m -> asin != null && asin.equals(toStr(m.get("asin"))))
                    .findFirst().orElse(null);
            if (match == null) {
                return ok(String.format("未查询到 ASIN %s 的 Listing 健康度记录，无法生成 SEO 建议", asin),
                        Map.of("asin", asin == null ? "" : asin, "found", false));
            }
            List<String> seo = new ArrayList<>();
            if (Boolean.FALSE.equals(toBool(match.get("titleOk")))) seo.add("优化标题：补充核心关键词至标题前部");
            if (Boolean.FALSE.equals(toBool(match.get("searchTermsOk")))) seo.add("补充后台搜索词(backend search terms)以覆盖长尾流量");
            if (Boolean.FALSE.equals(toBool(match.get("bulletPointsOk")))) seo.add("重写五点描述：突出卖点与关键词");
            if (Boolean.FALSE.equals(toBool(match.get("imagesOk")))) seo.add("增加主图与附图数量，提升点击率");
            if (Boolean.FALSE.equals(toBool(match.get("aplusOk")))) seo.add("补充 A+ 页面，提升转化");
            if (seo.isEmpty()) seo.add("Listing 各项指标健康，建议持续监控搜索词排名");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("asin", asin);
            out.put("healthScore", match.get("healthScore"));
            out.put("seoSuggestions", seo);
            out.put("methodNote", "SEO 建议由真实 Listing 健康度字段(titleOk/searchTermsOk/...)推导，缺失的具体关键词列表需结合搜索词报表进一步补全");
            return ok(String.format("ASIN %s SEO 优化建议：%d 条（基于真实健康度推导）", asin, seo.size()), out);
        } catch (Exception e) {
            log.error("optimize_listing_seo Feign 调用失败 shopId={}", shopId, e);
            return fail("商品服务暂时不可用: " + e.getMessage());
        }
    }
}
