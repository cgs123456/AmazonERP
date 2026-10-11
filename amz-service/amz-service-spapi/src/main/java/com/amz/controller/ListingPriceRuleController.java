package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.ListingsItemsClient;
import com.amz.client.SpApiOperationSpec;
import com.amz.model.ListingPriceRule;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Listing 调价规则接口（P3.2）。
 * <p>
 * 执行经统一 operation 执行器（listingsItems.patchListingsItem，官方快照
 * listingsItems_2021-08-01.json：PUT /listings/2021-08-01/items/{sellerId}/{sku}
 * body 必填 productType + patches）。本轮只做<b>手动触发</b>——定时自动执行等
 * 真实联调验证 patch 形状后再开（诚实边界）。
 */
@Slf4j
@RestController
@RequestMapping("/spapi/listing-price")
public class ListingPriceRuleController {

    private final ListingsItemsClient listingsItemsClient;
    private final com.amz.mapper.ListingPriceRuleMapper ruleMapper;
    private final com.amz.credential.ShopCredentialStore shopCredentialStore;

    public ListingPriceRuleController(ListingsItemsClient listingsItemsClient,
                                      com.amz.mapper.ListingPriceRuleMapper ruleMapper,
                                      com.amz.credential.ShopCredentialStore shopCredentialStore) {
        this.listingsItemsClient = listingsItemsClient;
        this.ruleMapper = ruleMapper;
        this.shopCredentialStore = shopCredentialStore;
    }

    /**
     * 新增/更新规则（同店+站点+SKU 幂等覆盖）。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "Listing调价", action = "维护", description = "新增或更新调价规则")
    @PostMapping
    public Result<ListingPriceRule> save(@RequestBody ListingPriceRule rule) {
        try {
            if (rule.getShopId() == null || rule.getMarketplaceId() == null
                    || rule.getSku() == null || rule.getSku().isBlank()) {
                return Result.failure("shopId / marketplaceId / sku 均不能为空",
                    com.amz.connector.ErrorSummary.localError(com.amz.connector.LocalApiException.CODE_INVALID_REQUEST));
            }
            if (rule.getTargetPrice() == null && rule.getAdjustPct() == null) {
                return Result.failure("目标价与调整幅度至少填一个",
                    com.amz.connector.ErrorSummary.localError(com.amz.connector.LocalApiException.CODE_INVALID_REQUEST));
            }
            if (rule.getTargetPrice() != null
                    && rule.getTargetPrice().compareTo(BigDecimal.ZERO) <= 0) {
                return Result.failure("目标价必须大于 0",
                    com.amz.connector.ErrorSummary.localError(com.amz.connector.LocalApiException.CODE_INVALID_REQUEST));
            }
            if (rule.getAdjustPct() != null
                    && (rule.getAdjustPct().compareTo(new BigDecimal("-1")) <= 0)) {
                return Result.failure("调整幅度不能低于 -100%（价格不可为负）",
                    com.amz.connector.ErrorSummary.localError(com.amz.connector.LocalApiException.CODE_INVALID_REQUEST));
            }
            rule.setSku(rule.getSku().trim());
            rule.setMarketplaceId(rule.getMarketplaceId().trim());
            ListingPriceRule existing = ruleMapper.selectOne(new LambdaQueryWrapper<ListingPriceRule>()
                    .eq(ListingPriceRule::getShopId, rule.getShopId())
                    .eq(ListingPriceRule::getMarketplaceId, rule.getMarketplaceId())
                    .eq(ListingPriceRule::getSku, rule.getSku()));
            if (existing != null) {
                rule.setId(existing.getId());
                ruleMapper.updateById(rule);
                return Result.success(rule);
            }
            rule.setId(null);
            rule.setEnabled(rule.getEnabled() == null ? 1 : rule.getEnabled());
            rule.setOperatorId(com.amz.context.UserContext.getUserId() == null
                    ? null : com.amz.context.UserContext.getUserId().longValue());
            ruleMapper.insert(rule);
            return Result.success(rule);
        } catch (Exception e) {
            log.warn("保存调价规则失败: {}", e.getMessage());
            return Result.failure(e.getMessage(),
                    com.amz.connector.ErrorSummary.toApiError(e));
        }
    }

    /**
     * 规则列表（按店铺）。
     */
    @ShopScoped
    @GetMapping("/list/{shopId}")
    public Result<List<ListingPriceRule>> list(@PathVariable Long shopId) {
        return Result.success(ruleMapper.selectList(new LambdaQueryWrapper<ListingPriceRule>()
                .eq(ListingPriceRule::getShopId, shopId)
                .orderByDesc(ListingPriceRule::getId)));
    }

    /**
     * 删除规则。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "Listing调价", action = "删除", description = "删除调价规则")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id, @RequestParam Long shopId) {
        ListingPriceRule rule = ruleMapper.selectById(id);
        if (rule == null || !rule.getShopId().equals(shopId)) {
            return Result.failure("规则不存在或无权访问",
                    com.amz.connector.ErrorSummary.localError(com.amz.connector.LocalApiException.CODE_INVALID_REQUEST));
        }
        ruleMapper.deleteById(id);
        return Result.success(true);
    }

    /**
     * 执行一条规则：经统一 operation 执行器下发 listingsItems patch。
     * <p>
     * 无真实凭证时统一执行器显式失败（API-ready 边界），规则记录 last_result 便于排查。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @OperLog(module = "Listing调价", action = "执行", description = "手动执行调价规则下发 Amazon")
    @PostMapping("/{id}/execute")
    public Result<ListingPriceRule> execute(@PathVariable Long id, @RequestParam Long shopId) {
        ListingPriceRule rule = ruleMapper.selectById(id);
        if (rule == null || !rule.getShopId().equals(shopId)) {
            return Result.failure("规则不存在或无权访问",
                    com.amz.connector.ErrorSummary.localError(com.amz.connector.LocalApiException.CODE_INVALID_REQUEST));
        }
        if (rule.getEnabled() == null || rule.getEnabled() != 1) {
            return Result.failure("规则已停用，请先启用",
                    com.amz.connector.ErrorSummary.localError(com.amz.connector.LocalApiException.CODE_INVALID_REQUEST));
        }
        try {
            SpApiOperationSpec spec = com.amz.client.SpApiOperationCatalog
                    .find("listingsItems.patchListingsItem")
                    .orElseThrow(() -> new IllegalStateException("catalog 缺少 listingsItems.patchListingsItem"));
            JsonObject body = new JsonObject();
            body.addProperty("productType", "PRODUCT");
            com.google.gson.JsonArray patches = new com.google.gson.JsonArray();
            JsonObject patch = new JsonObject();
            patch.addProperty("op", "replace");
            patch.addProperty("path", "/attributes/purchasable_offer");
            JsonObject value = new JsonObject();
            value.addProperty("marketplace_id", rule.getMarketplaceId());
            com.google.gson.JsonArray audience = new com.google.gson.JsonArray();
            audience.add("ALL");
            value.add("audience", audience);
            com.google.gson.JsonArray price = new com.google.gson.JsonArray();
            JsonObject priceObj = new JsonObject();
            priceObj.addProperty("amount", resolveTargetPrice(rule).toPlainString());
            priceObj.addProperty("currency", "USD");
            price.add(priceObj);
            value.add("price", price);
            patch.add("value", value);
            patches.add(patch);
            body.add("patches", patches);

            JsonObject response = listingsItemsClient.patchListingsItem(
                    shopId, rule.getMarketplaceId(), resolveSellerId(shopId), rule.getSku(),
                    List.of(rule.getMarketplaceId()), body);
            String summary = "已下发 Amazon（ submissionId="
                    + (response.has("submissionId") ? response.get("submissionId").getAsString() : "未知") + "）";
            rule.setLastExecutedAt(LocalDateTime.now());
            rule.setLastResult(summary);
            ruleMapper.updateById(rule);
            return Result.success(rule);
        } catch (Exception e) {
            String summary = "失败：" + com.amz.connector.ErrorSummary.of(e);
            rule.setLastExecutedAt(LocalDateTime.now());
            rule.setLastResult(summary);
            ruleMapper.updateById(rule);
            log.warn("调价规则执行失败 id={}: {}", id, e.getMessage());
            return Result.failure("调价执行失败：" + summary, com.amz.connector.ErrorSummary.toApiError(e));
        }
    }

    /**
     * sellerId 来自店铺凭证（amz_shop_credential.seller_id）——
     * listingsItems 路径需要它；凭证缺失时显式失败。
     */
    private String resolveSellerId(Long shopId) {
        var credential = shopCredentialStore.get(shopId);
        if (credential == null || credential.getSellerId() == null
                || credential.getSellerId().isBlank()) {
            throw com.amz.connector.LocalApiException.of(
                    com.amz.connector.LocalApiException.CODE_CREDENTIAL_MISSING,
                    "店铺凭证缺少 sellerId，无法定位 listing：shopId=" + shopId);
        }
        return credential.getSellerId();
    }

    /** 目标价：直接目标价；或按幅度（本轮无当前价来源——幅度规则必须配 target_price 执行）。 */
    private BigDecimal resolveTargetPrice(ListingPriceRule rule) {
        if (rule.getTargetPrice() != null) {
            return rule.getTargetPrice();
        }
        throw com.amz.connector.LocalApiException.of(
                com.amz.connector.LocalApiException.CODE_INVALID_REQUEST,
                "幅度规则需要当前价来源（产品目录），本轮仅支持目标价规则：sku=" + rule.getSku());
    }
}
