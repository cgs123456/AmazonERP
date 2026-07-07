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
 * Temu 开放平台模拟客户端。
 * <p>
 * 生产环境对接路径（参考 Temu Seller Center Open API）：
 * <ol>
 *   <li>/order/list - 拉取订单列表</li>
 *   <li>/order/detail - 查询订单详情（含 SKU、收货地址）</li>
 *   <li>/logistics/ship - 上传发货信息与物流单号</li>
 * </ol>
 * 鉴权方式：appKey + appSecret 生成 sign，请求头透传 access_token。
 * <p>
 * 当前为离线模拟实现，返回构造的 Temu 订单，保证项目可独立运行。
 */
@Slf4j
@Component
public class TemuClient {

    @Value("${platform.temu.app-key:}")
    private String appKey;

    @Value("${platform.temu.app-secret:}")
    private String appSecret;

    /**
     * 拉取 Temu 近 24 小时新订单（模拟）。
     *
     * @param shopId 店铺 ID
     * @return Temu 订单列表
     */
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        log.info("Temu 订单拉取模拟：shopId={} appKey={}", shopId, mask(appKey));
        List<UnifiedOrder> orders = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        UnifiedOrder o1 = new UnifiedOrder();
        o1.setPlatform("TEMU");
        o1.setPlatformOrderNo("TEMU-" + System.currentTimeMillis());
        o1.setShopId(shopId);
        o1.setBuyerNickname("temu_buyer_001");
        o1.setShipCountry("US");
        o1.setSku("SKU-TEMU-001");
        o1.setProductName("无线蓝牙耳机");
        o1.setQuantity(2);
        o1.setOriginalAmount(new BigDecimal("29.98"));
        o1.setCurrency("USD");
        o1.setStatus("PAID");
        o1.setOrderCreateTime(LocalDateTime.now().format(fmt));
        orders.add(o1);

        UnifiedOrder o2 = new UnifiedOrder();
        o2.setPlatform("TEMU");
        o2.setPlatformOrderNo("TEMU-" + (System.currentTimeMillis() + 1));
        o2.setShopId(shopId);
        o2.setBuyerNickname("temu_buyer_002");
        o2.setShipCountry("DE");
        o2.setSku("SKU-TEMU-002");
        o2.setProductName("USB-C 快充线");
        o2.setQuantity(5);
        o2.setOriginalAmount(new BigDecimal("14.95"));
        o2.setCurrency("EUR");
        o2.setStatus("UNPAID");
        o2.setOrderCreateTime(LocalDateTime.now().format(fmt));
        orders.add(o2);
        return orders;
    }

    /**
     * 向 Temu 回传发货信息（模拟）。
     */
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        log.info("Temu 发货回传模拟：orderNo={} trackingNo={}", platformOrderNo, trackingNo);
        return true;
    }

    private String mask(String key) {
        if (key == null || key.length() < 4) {
            return "****";
        }
        return key.substring(0, 2) + "****" + key.substring(key.length() - 2);
    }
}
