package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.client.OrderServiceFeignClient;
import com.amz.context.UserContext;
import com.amz.model.ListingCopyTask;
import com.amz.result.Result;
import com.amz.service.ListingCopyService;
import com.amz.service.TranslationService;
import com.amz.util.MapArgUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/product")
public class ProductController {

    /** 兜底默认 fulfillmentFee（kg 单位/standard 档估算值，仅 Feign 失败时使用）。 */
    private static final BigDecimal DEFAULT_FULFILLMENT_FEE_STANDARD = new BigDecimal("3.22");
    private static final BigDecimal DEFAULT_FULFILLMENT_FEE_OVERSIZE = new BigDecimal("5.45");
    /** 兜底默认 storageFee 每月每公斤系数（估算值）。 */
    private static final BigDecimal DEFAULT_STORAGE_FEE_PER_KG = new BigDecimal("0.87");


    @Autowired
    private ListingCopyService listingCopyService;

    @Autowired
    private TranslationService translationService;

    @Autowired
    private OrderServiceFeignClient orderServiceFeignClient;


    /**
     * 跨站点 Listing 复制（供 Agent 工具调用）。
     * POST /product/listing/copy
     * sku 与 asin 必须二选一；ASIN 会先解析为真实卖家 SKU。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/listing/copy")
    public Result<Map<String, Object>> copyListing(@RequestBody Map<String, Object> req) {
        Long shopId = toLong(req.get("shopId"));
        String sku = toStr(req.get("sku"));
        String asin = toStr(req.get("asin"));
        String sourceMarketplaceId = toStr(req.get("sourceMarketplaceId"));
        String targetMarketplaceId = toStr(req.get("targetMarketplaceId"));
        String targetLanguage = toStr(req.get("targetLanguage"));
        BigDecimal priceMarkup = toBigDecimal(req.get("priceMarkup"), new BigDecimal("0.20"));

        boolean hasSku = sku != null && !sku.isBlank();
        boolean hasAsin = asin != null && !asin.isBlank();
        if (hasSku == hasAsin) {
            return Result.failure("sku 与 asin 必须二选一");
        }
        if (shopId == null || sourceMarketplaceId == null || targetMarketplaceId == null) {
            return Result.failure("shopId/sourceMarketplaceId/targetMarketplaceId 不能为空");
        }
        // shopId 来自请求体，必须使用外部边界严格校验，不能走兼容内部调用的宽松语义。
        if (!UserContext.isShopAllowedStrict(shopId)) {
            log.warn("Listing 复制越权拦截：shopId={}, userId={}", shopId, UserContext.getUserId());
            return Result.failure("无权操作该店铺");
        }
        try {
            ListingCopyTask task = hasSku
                    ? listingCopyService.createCopyTask(shopId, sku, sourceMarketplaceId,
                            targetMarketplaceId, targetLanguage, priceMarkup)
                    : listingCopyService.createCopyTaskByAsin(shopId, asin, sourceMarketplaceId,
                            targetMarketplaceId, targetLanguage, priceMarkup);
            Map<String, Object> data = new HashMap<>();
            data.put("taskId", task.getId());
            data.put("shopId", task.getShopId());
            data.put("sku", task.getSku());
            data.put("productType", task.getProductType());
            data.put("sourceMarketplaceId", task.getSourceMarketplaceId());
            data.put("targetMarketplaceId", task.getTargetMarketplaceId());
            data.put("targetLanguage", task.getTargetLanguage());
            data.put("status", task.getStatus());
            data.put("sourceTitle", task.getSourceTitle());
            data.put("sourcePrice", task.getSourcePrice());
            data.put("priceMarkup", task.getPriceMarkup());
            return Result.success(data);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (SecurityException e) {
            return Result.failure("无权操作该店铺");
        }
    }

    /**
     * 多语种翻译（供 Agent 工具调用）。
     * POST /product/translate
     */
    @PostMapping("/translate")
    public Result<Map<String, Object>> translate(@RequestBody Map<String, Object> req) {
        String text = toStr(req.get("text"));
        String sourceLang = toStr(req.get("sourceLang"));
        String targetLang = toStr(req.get("targetLang"));
        if (sourceLang == null) sourceLang = "en";
        if (targetLang == null) targetLang = "de";

        if (text == null || text.trim().isEmpty()) {
            return Result.failure("text 不能为空");
        }
        String translated = translationService.translate(text, sourceLang, targetLang);
        Map<String, Object> data = new HashMap<>();
        data.put("sourceText", text);
        data.put("translatedText", translated);
        data.put("sourceLang", sourceLang);
        data.put("targetLang", targetLang);
        return Result.success(data);
    }

    /**
     * FBA 费用估算（供 Agent 工具调用）。
     * GET /product/fees/estimate?asin=&price=&weight=&sizeTier=
     * <p>
     * 优先通过 Feign 调用 amz-service-order 的 {@code /order/fees/lookup} 接口查询
     * 真实 FBA 费率表（amz_fba_fee_table）。
     * <p>
     * 降级策略：当订单服务不可达或未配置费率表时，回退到原硬编码估算值
     * （fulfillmentFee: 3.22 / 5.45；storageFee: weight * 0.87），
     * 并在响应中通过 {@code source="estimated"} 标记。
     */
    @GetMapping("/fees/estimate")
    public Result<Map<String, Object>> estimateFbaFees(
            @RequestParam(required = false) String asin,
            @RequestParam(required = false) Double price,
            @RequestParam(required = false) Double weight,
            @RequestParam(required = false) String sizeTier) {
        if (weight == null || weight <= 0) weight = 0.5;
        if (sizeTier == null || sizeTier.isBlank()) sizeTier = "standard";

        BigDecimal fulfillmentFee;
        BigDecimal storageFee;
        String source = "real";
        Integer matchedWeightG = null;

        // 1. 优先通过 Feign 查询真实费率表
        Map<String, Object> feeData = lookupRealFbaFees(sizeTier, weight);
        if (feeData != null) {
            fulfillmentFee = toBigDecimal(feeData.get("fulfillmentFee"), BigDecimal.ZERO);
            BigDecimal storageFeePerMonth = toBigDecimal(feeData.get("storageFeePerMonth"), null);
            Object weightG = feeData.get("weightG");
            if (weightG instanceof Number) {
                matchedWeightG = ((Number) weightG).intValue();
            }
            if (storageFeePerMonth != null) {
                storageFee = storageFeePerMonth.setScale(2, RoundingMode.HALF_UP);
            } else {
                // 表里有 fulfillmentFee 但无 storageFee 时按重量估算（与原默认逻辑一致）
                storageFee = new BigDecimal(weight.toString())
                        .multiply(DEFAULT_STORAGE_FEE_PER_KG)
                        .setScale(2, RoundingMode.HALF_UP);
                source = "estimated_storage_only";
            }
        } else {
            // 2. Feign 失败/无匹配：降级到原硬编码估算值
            fulfillmentFee = "standard".equalsIgnoreCase(sizeTier)
                    ? DEFAULT_FULFILLMENT_FEE_STANDARD : DEFAULT_FULFILLMENT_FEE_OVERSIZE;
            storageFee = new BigDecimal(weight.toString())
                    .multiply(DEFAULT_STORAGE_FEE_PER_KG)
                    .setScale(2, RoundingMode.HALF_UP);
            source = "estimated";
        }

        BigDecimal totalFbaFee = fulfillmentFee.add(storageFee)
                .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> data = new HashMap<>();
        data.put("asin", asin);
        data.put("price", price);
        data.put("sku", null);
        data.put("sizeTier", sizeTier);
        data.put("weight", weight);
        data.put("fulfillmentFee", fulfillmentFee);
        data.put("storageFee", storageFee);
        data.put("totalFbaFee", totalFbaFee);
        data.put("source", source);
        data.put("matchedWeightG", matchedWeightG);
        return Result.success(data);
    }

    /**
     * 通过 Feign 调用 order 服务查询 FBA 真实费率。
     * 失败或无匹配时返回 null（调用方降级）。
     */
    private Map<String, Object> lookupRealFbaFees(String sizeTier, Double weightKg) {
        try {
            // weight 单位为 kg，转 g 查询
            Integer weightG = (int) Math.max(1, Math.round(weightKg * 1000));
            Result<Map<String, Object>> resp = orderServiceFeignClient.lookupFbaFees(sizeTier, weightG);
            if (resp == null || resp.getCode() != 200) {
                log.warn("FBA 费率查询返回非 200：sizeTier={}, weightKg={}, resp={}", sizeTier, weightKg, resp);
                return null;
            }
            Map<String, Object> data = resp.getData();
            if (data == null || data.get("fulfillmentFee") == null) {
                return null;
            }
            return data;
        } catch (Exception e) {
            log.warn("Feign 调用 amz-service-order 费率查询失败，降级到估算默认值：sizeTier={}, weightKg={}, error={}",
                    sizeTier, weightKg, e.getMessage());
            return null;
        }
    }

    private Long toLong(Object obj) {
        return MapArgUtils.toLong(obj);
    }

    private String toStr(Object obj) {
        return MapArgUtils.toStr(obj);
    }

    private BigDecimal toBigDecimal(Object obj, BigDecimal defaultValue) {
        return MapArgUtils.toBigDecimal(obj, defaultValue);
    }
}
