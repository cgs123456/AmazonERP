package com.amz.client.fallback;

import com.amz.client.SpApiShipmentClient;
import com.amz.client.dto.ShipmentConfirmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * SpApiShipmentClient 降级工厂（fail-closed）。
 * <p>
 * spapi 不可用时返回 {@code false}，调用方据此<b>不</b>改本地订单状态。
 * <p>
 * 这里刻意<b>不</b>照抄 {@code FinanceServiceFeignClientFallbackFactory} 的静默跳过：
 * 凭证漏生成可以事后按订单号补，而发货状态一旦在本地标成「已回传」但 Amazon 侧
 * 从未收到，就是「系统说发了、平台认为没发」——超时未发货会直接影响卖家账号绩效。
 * 所以降级只能表达「没成功」，不能表达「成功」。
 */
@Slf4j
@Component
public class SpApiShipmentClientFallbackFactory implements FallbackFactory<SpApiShipmentClient> {

    @Override
    public SpApiShipmentClient create(Throwable cause) {
        log.warn("Feign call to amz-service-spapi (shipment confirm) degraded", cause);
        return new SpApiShipmentClient() {
            @Override
            public Boolean confirmShipment(ShipmentConfirmRequest request) {
                log.warn("发货回传降级：spapi 不可用，本地状态保持未回传。shopId={} orderId={}",
                        request == null ? null : request.getShopId(),
                        request == null ? null : request.getAmazonOrderId());
                return Boolean.FALSE;
            }
        };
    }
}
