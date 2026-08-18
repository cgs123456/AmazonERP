package com.amz.client;

import com.amz.model.UnifiedOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Shein 开放平台模拟客户端。
 * <p>
 * 离线模拟实现。仅在 {@code spring.profiles.active=mock} 时生效。
 */
@Slf4j
@Component
@Profile("mock")
public class SheinMockClient extends AbstractPlatformClient implements SheinClient {

    @Override
    protected String getPlatform() {
        return PLATFORM_SHEIN;
    }

    @Override
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        log.info("Shein 订单拉取模拟：shopId={} appKey={}", shopId, mask(cred(shopId).getAppKey()));
        List<UnifiedOrder> orders = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        UnifiedOrder o1 = new UnifiedOrder();
        o1.setPlatform(PLATFORM_SHEIN);
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

    @Override
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        log.info("Shein 发货回传模拟：orderNo={} trackingNo={}", platformOrderNo, trackingNo);
        return true;
    }
}
