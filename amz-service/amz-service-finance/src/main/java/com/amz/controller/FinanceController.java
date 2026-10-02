package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.dto.KingdeeSyncResult;
import com.amz.model.AccountingVoucher;
import com.amz.result.Result;
import com.amz.result.PageRequest;
import com.amz.service.FinanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业财一体化 REST 端点。
 */
@RestController
@RequestMapping("/finance")
public class FinanceController {

    @Autowired
    private FinanceService financeService;

    /**
     * 根据订单自动生成会计凭证（多币种换算）。
     * POST /finance/voucher/order
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/voucher/order")
    public Result<AccountingVoucher> generateOrderVoucher(
            @RequestParam Long shopId,
            @RequestParam String orderNo,
            @RequestParam BigDecimal amount,
            @RequestParam String currency) {
        return Result.success(financeService.generateOrderVoucher(shopId, orderNo, amount, currency));
    }

    /**
     * 由结算行补齐 PLATFORM_FEE / REFUND 凭证（幂等，可重复调用）。
     * POST /finance/voucher/from-settlement?shopId=
     * <p>
     * 利润口径一直在减这两类，此前没有任何生产者写入，导致利润只剩收入侧、系统性偏高。
     * 返回报告把「扫了多少行 / 生成了几张 / 为什么跳过」分开计数，
     * capped=true 说明命中扫描上限、需要再跑一次。
     */
    @ShopScoped
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/voucher/from-settlement")
    public Result<com.amz.dto.SettlementVoucherReport> generateSettlementVouchers(@RequestParam Long shopId) {
        return Result.success(financeService.generateSettlementVouchers(shopId));
    }

    /**
     * 同步凭证到金蝶。
     * POST /finance/voucher/{voucherId}/sync
     * <p>
     * RBAC：写操作要求 OPERATOR 及以上；店铺归属在服务层校验。
     */
    @RequireRole({"OPERATOR", "ADMIN"})
    @PostMapping("/voucher/{voucherId}/sync")
    public Result<KingdeeSyncResult> syncToKingdee(@PathVariable Long voucherId) {
        KingdeeSyncResult syncResult = financeService.syncToKingdee(voucherId);
        if (!syncResult.isFailure()) {
            return Result.success(syncResult);
        }
        // 保留结构化结果，前端/自动化可以区分 NOT_CONFIGURED、FORBIDDEN、FAILED 等，
        // 不再把失败压扁成只有一句 message 的 boolean。
        Result<KingdeeSyncResult> failure = Result.failure(syncResult.message());
        failure.setData(syncResult);
        return failure;
    }

    /**
     * 查询凭证列表（游标分页）。
     * GET /finance/voucher/list/{shopId}?sourceType=&size=&cursor=
     * <p>
     * 响应在 {@code data} 之外多出 {@code _page}：
     * {@code {size, returned, hasMore, truncated, nextCursor, total}}。
     * {@code data} 仍直接是凭证数组，老客户端零改造继续可用；
     * 新客户端必须检查 {@code truncated}，否则会拿第一页当全量去对账。
     * <p>
     * {@code size} 超过 {@link PageRequest#MAX_SIZE} 或 {@code cursor} 不可解析
     * 一律 400（InvalidParamException），不静默收敛、不静默回退首页。
     */
    @ShopScoped
    @GetMapping("/voucher/list/{shopId}")
    public Result<List<AccountingVoucher>> listVouchers(
            @PathVariable Long shopId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        return Result.paged(financeService.listVouchers(shopId, sourceType, PageRequest.of(size, cursor)));
    }

    /**
     * 查询店铺利润（CNY）。
     * GET /finance/profit/{shopId}?startDate=&endDate=
     */
    @ShopScoped
    @GetMapping("/profit/{shopId}")
    public Result<BigDecimal> calculateProfit(
            @PathVariable Long shopId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return Result.success(financeService.calculateProfit(shopId, startDate, endDate));
    }
}
