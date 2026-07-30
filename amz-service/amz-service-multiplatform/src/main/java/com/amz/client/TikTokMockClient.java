package com.amz.client;

import com.amz.model.UnifiedOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * TikTok Shop 开放平台模拟客户端。
 * <p>
 * 离线模拟实现。仅在 {@code spring.profiles.active=mock} 时生效。
 */
@Slf4j
@Component
@Profile("mock")
public class TikTokMockClient extends AbstractPlatformClient implements TikTokClient {

    @Value("${platform.tiktok.app-key:}")
    private String appKey;

    @Value("${platform.tiktok.app-secret:}")
    private String appSecret;

    @Override
    protected String getPlatform() {
        return PLATFORM_TIKTOK;
    }

    @Override
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        log.info("TikTok Shop 订单拉取模拟：shopId={} appKey={}", shopId, mask(appKey));
        List<UnifiedOrder> orders = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        UnifiedOrder o1 = new UnifiedOrder();
        o1.setPlatform(PLATFORM_TIKTOK);
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

    @Override
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        log.info("TikTok Shop 发货回传模拟：orderNo={} trackingNo={}", platformOrderNo, trackingNo);
        return true;
    }
}
