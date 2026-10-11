package com.amz.client;

import com.amz.client.dto.ShipmentConfirmRequest;
import com.amz.client.fallback.SpApiShipmentClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * SP-API 发货回传 Feign 客户端。
 * <p>
 * order 域在本地确认发货时调用 {@code amz-service-spapi} 的
 * {@code POST /spapi/shipment/confirm}，由连接器层用共享网关把确认发到 Amazon
 * （凭证、区域端点、限流、Outbox 都在那一侧）。
 * <p>
 * <b>为什么必须有这个 client 而不是走 MQ</b>：发货回传需要<b>同步拿到结果</b>——
 * 「Amazon 是否受理」决定本地订单状态改不改。走 MQ 会把它变成异步，
 * 调用方拿不到结果就只能猜，而猜错的方向恰恰是「没回传成功却标了已发货」。
 * <p>
 * <b>降级策略是 fail-closed</b>：spapi 不可用时返回 false，调用方据此<b>不</b>改本地状态。
 * 这与 {@code FinanceServiceFeignClient} 的「静默降级跳过」不同——凭证可以事后补生成，
 * 发货状态标错了会直接导致 Amazon 侧超时未发货（账号绩效问题），不可静默。
 */
@Component
@FeignClient(name = "amz-service-spapi", contextId = "spApiShipmentClient",
        fallbackFactory = SpApiShipmentClientFallbackFactory.class)
public interface SpApiShipmentClient {

    /**
     * 请求 spapi 向 Amazon 确认发货。
     *
     * @param request 发货回传请求（店铺、站点、订单号、承运商、运单号、逐行明细）
     * @return true 表示 Amazon 已受理；false 表示调用未成功（含降级），调用方不得据此改本地状态
     */
    @PostMapping("/spapi/shipment/confirm")
    Boolean confirmShipment(@RequestBody ShipmentConfirmRequest request);
}
