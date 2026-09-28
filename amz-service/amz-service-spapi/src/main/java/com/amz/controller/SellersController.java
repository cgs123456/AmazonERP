package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.SellersClient;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Amazon SP-API Sellers 只读接口。
 *
 * <p>当前仅暴露无 PII 的 marketplace participations 查询。真实客户端只在本服务以
 * 非 {@code mock} profile 启动时装配；没有凭证或凭证无效时由网关统一显式失败，
 * 不会退化为空成功。
 */
@RestController
@RequestMapping("/spapi/sellers")
@Profile("!mock")
public class SellersController {

    private static final Logger log = LoggerFactory.getLogger(SellersController.class);

    @Autowired
    private SellersClient client;

    /**
     * 查询授权店铺可参与的 marketplace。
     *
     * @param shopId        店铺 ID，由 {@link ShopScoped} 做多租户二次校验
     * @param marketplaceId 可选 marketplace 覆盖；空值时使用凭证登记的 marketplace
     * @return 官方响应（含 payload）；失败时返回结构化错误，不返回空成功
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @ShopScoped
    @GetMapping("/marketplace-participations")
    public Result<JsonObject> getMarketplaceParticipations(
            @RequestParam Long shopId,
            @RequestParam(required = false) String marketplaceId) {
        if (shopId == null) {
            return Result.failure("shopId must not be null",
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }
        String marketplaceOverride = marketplaceId == null || marketplaceId.isBlank()
                ? null : marketplaceId.trim();
        try {
            return Result.success(client.getMarketplaceParticipations(shopId, marketplaceOverride));
        } catch (Exception e) {
            log.error("SellersController.getMarketplaceParticipations failed shopId={}", shopId, e);
            return Result.failure(
                    "marketplace participations failed: " + ErrorSummary.of(e),
                    ErrorSummary.toApiError(e));
        }
    }
}
