package com.amz.client;

import com.amz.client.fallback.FinanceServiceFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 财务服务 Feign 客户端（业财一体化打通）。
 * <p>
 * 订单落库后由 {@code OrderServiceImpl.syncAmazonOrder} 调用
 * {@link #generateOrderVoucher(Long, String, BigDecimal, String)} 触发凭证生成，
 * 实现「订单 → 凭证」业财一体化。返回值用 {@code void}，订单服务不依赖
 * 财务模块的 AccountingVoucher 实体（避免跨服务模块类依赖）。
 * <p>
 * 降级策略：通过 {@link FinanceServiceFeignClientFallbackFactory} 在财务服务
 * 不可用时静默降级（打 warn 日志），不阻断订单落库主流程（凭证可后续按订单号补生成）。
 */
@Component
@FeignClient(name = "amz-service-finance", fallbackFactory = FinanceServiceFeignClientFallbackFactory.class)
public interface FinanceServiceFeignClient {

    /**
     * 根据订单生成会计凭证（借应收 / 贷收入 + 多币种换算）。
     *
     * @param shopId   店铺 ID
     * @param orderNo  订单号
     * @param amount   订单金额（原币）
     * @param currency 币种
     */
    @PostMapping("/finance/voucher/order")
    void generateOrderVoucher(@RequestParam("shopId") Long shopId,
                              @RequestParam("orderNo") String orderNo,
                              @RequestParam("amount") BigDecimal amount,
                              @RequestParam("currency") String currency);
}
