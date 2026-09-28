package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.connector.ErrorSummary;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredentialStore;
import com.amz.preflight.PreflightReport;
import com.amz.preflight.SpApiConnectivityPreflight;
import com.amz.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 连通性自检端点（P1：凭证到位那天的一条命令判据）。
 *
 * <p><b>为什么单独开一个 Controller：</b>{@code ConnectorController#selfTest}
 * 走的是 orders.getOrders，回答「业务链路通不通」；本端点走
 * {@link SpApiConnectivityPreflight}，回答「能不能连通、卡在哪一段」。
 * 两者互补，合并会把「鉴权失败」和「订单查询失败」重新混成一个 outcomeCode。
 *
 * <p><b>守卫：</b>自检会触发真实出网，且报告含店铺与端点信息，
 * 因此只放行 OPERATOR / ADMIN，并逐店铺走
 * {@link UserContext#isShopAllowedStrict(Long)}——不能因为「只是读」就放开。
 *
 * <p><b>上限：</b>{@code limit} 硬上限 50（P1-02 无界列表风险）。
 * 自检是<b>逐店铺真实出网</b>的操作，不设上限等于给自己造一个放大流量的入口。
 */
@RestController
@RequestMapping("/spapi/preflight")
public class PreflightController {

    private static final Logger log = LoggerFactory.getLogger(PreflightController.class);

    /** 批量自检的硬上限：自检逐店铺真实出网，绝不接受无界参数。 */
    public static final int MAX_LIMIT = 50;

    private final SpApiConnectivityPreflight preflight;
    private final ShopCredentialStore credentialStore;

    public PreflightController(SpApiConnectivityPreflight preflight, ShopCredentialStore credentialStore) {
        this.preflight = preflight;
        this.credentialStore = credentialStore;
    }

    /**
     * 单店铺分层自检。
     *
     * @param shopId            店铺 ID
     * @param forceTokenRefresh true 时先失效 token 缓存再自检（刚换过 refresh_token 必须用这个，
     *                          否则复用旧 token 会给出假绿灯）
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/shop/{shopId}")
    public Result<PreflightReport> one(@PathVariable Long shopId,
                                       @RequestParam(defaultValue = "false") boolean forceTokenRefresh) {
        if (!UserContext.isShopAllowedStrict(shopId)) {
            log.warn("[preflight] 越权拦截：userId={}, role={}, shopId={}",
                    UserContext.getUserId(), UserContext.getRole(), shopId);
            return Result.failure("无权自检该店铺 shopId=" + shopId,
                    ErrorSummary.localError("PREFLIGHT_FORBIDDEN"));
        }
        return Result.success(preflight.check(shopId, forceTokenRefresh));
    }

    /**
     * 批量自检当前用户可见的全部店铺。
     *
     * <p>只自检该用户有权限的店铺；ADMIN 之外不把「无店铺授权」解释成全量范围。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping
    public Result<List<PreflightReport>> all(@RequestParam(defaultValue = "false") boolean forceTokenRefresh,
                                             @RequestParam(defaultValue = "20") int limit) {
        if (limit <= 0 || limit > MAX_LIMIT) {
            return Result.failure("limit 必须在 1.." + MAX_LIMIT + " 之间，当前值=" + limit,
                    ErrorSummary.localError("INVALID_REQUEST"));
        }
        List<Long> shopIds = new ArrayList<>(credentialStore.getActiveShopIds());
        List<PreflightReport> reports = new ArrayList<>();
        int checked = 0;
        for (Long shopId : shopIds) {
            if (checked >= limit) {
                break;
            }
            if (!UserContext.isShopAllowedStrict(shopId)) {
                continue;
            }
            reports.add(preflight.check(shopId, forceTokenRefresh));
            checked++;
        }
        return Result.success(reports);
    }
}
