package com.amz.client;

import com.amz.model.UnifiedOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Shein 开放平台模拟客户端。
 * <p>
 * 生产环境对接路径（参考 Shein Open Platform）：
 * <ol>
 *   <li>/open/orders/list - 订单列表</li>
 *   <li>/open/orders/detail - 订单详情</li>
 *   <li>/open/logistics/upload - 物流单号回传</li>
 * </ol>
 * 鉴权方式：appKey + appSecret 生成 sign。
 * <p>
 * 当前为离线模拟实现。
 */
@Slf4j
@Component
public class SheinClient {

    @Value("${platform.shein.app-key:}")
    private String appKey;

    @Value("${platform.shein.app-secret:}")
    private String appSecret;

    /**
     * 拉取 Shein 近 24 小时新订单（模拟）。
     */
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        log.info("Shein 订单拉取模拟：shopId={} appKey={}", shopId, mask(appKey));
        List<UnifiedOrder> orders = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        UnifiedOrder o1 = new UnifiedOrder();
        o1.setPlatform("SHEIN");
        o1.setPlatformOrderNo("SH-" + System.currentTimeMillis());
        o1.setShopId(shopId);
        o1.setBuyerNickname("shein_buyer_5566");
        o1.setShipCountry("FR");
        o1.setSku("SKU-SH-001");
        o1.setProductName("可折叠收纳盒");
        o1.setQuantity(3);
        o1.setOriginalAmount(new BigDecimal("19.50"));
        o1.setCurrency("EUR");
        o1.setStatus("PAID");
        o1.setOrderCreateTime(LocalDateTime.now().format(fmt));
        orders.add(o1);
        return orders;
    }

    /**
     * 向 Shein 回传发货信息（模拟）。
     */
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        log.info("Shein 发货回传模拟：orderNo={} trackingNo={}", platformOrderNo, trackingNo);
        return true;
    }

    private String mask(String key) {
        if (key == null || key.length() < 4) {
            return "****";
        }
        return key.substring(0, 2) + "****" + key.substring(key.length() - 2);
    }
}
