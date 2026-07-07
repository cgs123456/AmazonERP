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
 * TikTok Shop 开放平台模拟客户端。
 * <p>
 * 生产环境对接路径（参考 TikTok Shop API）：
 * <ol>
 *   <li>/orders/search - 订单搜索（按时间/状态过滤）</li>
 *   <li>/orders/{order_id} - 订单详情</li>
 *   <li>/fulfillment/{order_id}/ship - 上传物流单号</li>
 * </ol>
 * 鉴权方式：appKey + appSecret 换取 access_token，请求头透传。
 * <p>
 * 当前为离线模拟实现。
 */
@Slf4j
@Component
public class TikTokClient {

    @Value("${platform.tiktok.app-key:}")
    private String appKey;

    @Value("${platform.tiktok.app-secret:}")
    private String appSecret;

    /**
     * 拉取 TikTok Shop 近 24 小时新订单（模拟）。
     */
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        log.info("TikTok Shop 订单拉取模拟：shopId={} appKey={}", shopId, mask(appKey));
        List<UnifiedOrder> orders = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        UnifiedOrder o1 = new UnifiedOrder();
        o1.setPlatform("TIKTOK");
        o1.setPlatformOrderNo("TK-" + System.currentTimeMillis());
        o1.setShopId(shopId);
        o1.setBuyerNickname("tt_buyer_8848");
        o1.setShipCountry("UK");
        o1.setSku("SKU-TK-001");
        o1.setProductName("迷你投影仪");
        o1.setQuantity(1);
        o1.setOriginalAmount(new BigDecimal("89.99"));
        o1.setCurrency("GBP");
        o1.setStatus("PAID");
        o1.setOrderCreateTime(LocalDateTime.now().format(fmt));
        orders.add(o1);
        return orders;
    }

    /**
     * 向 TikTok Shop 回传发货信息（模拟）。
     */
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        log.info("TikTok Shop 发货回传模拟：orderNo={} trackingNo={}", platformOrderNo, trackingNo);
        return true;
    }

    private String mask(String key) {
        if (key == null || key.length() < 4) {
            return "****";
        }
        return key.substring(0, 2) + "****" + key.substring(key.length() - 2);
    }
}
