package com.amz.client;

import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * mock profile 专用的样例数据工厂。
 * <p>
 * 这些形状是从 {@code MultiplatformServiceImpl} 原样搬来的：字段、条数、取值区间都不变，
 * 因为演示栈（{@code SPRING_PROFILES_ACTIVE=mock}）的前端页面依赖它们有内容可看。
 * 唯一变化是**归属**：造数只发生在 mock 实现里，真实实现必须真调用或显式失败。
 */
final class PlatformMockSamples {

    private static final String[] MESSAGE_SUBJECTS = {
            "Order Question", "Return Request", "Product Inquiry", "Shipping Delay"};

    private static final String[] INVENTORY_SKUS = {"SKU-A", "SKU-B", "SKU-C", "SKU-D"};

    private PlatformMockSamples() {
    }

    static List<PlatformProduct> products(String platform, Long shopId) {
        List<PlatformProduct> list = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            PlatformProduct pp = new PlatformProduct();
            pp.setShopId(shopId);
            pp.setPlatform(platform);
            pp.setPlatformProductId(platform + "-PROD-" + shopId + "-" + i);
            pp.setPlatformProductSku("SKU-" + platform + "-" + i);
            pp.setTitle("Platform Product " + i + " from " + platform + " shop " + shopId);
            pp.setPrice(new BigDecimal((10 + i) + ".99"));
            pp.setCurrency("USD");
            pp.setStockQty(100 - i * 10);
            pp.setStatus("ACTIVE");
            pp.setCategory("General");
            pp.setCreateTime(LocalDateTime.now());
            pp.setUpdateTime(LocalDateTime.now());
            list.add(pp);
        }
        return list;
    }

    static List<PlatformMessage> messages(String platform, Long shopId) {
        List<PlatformMessage> list = new ArrayList<>();
        for (int i = 0; i < MESSAGE_SUBJECTS.length; i++) {
            PlatformMessage msg = new PlatformMessage();
            msg.setShopId(shopId);
            msg.setPlatform(platform);
            msg.setPlatformMessageId(platform + "-MSG-" + shopId + "-" + System.currentTimeMillis() + "-" + i);
            msg.setBuyerName("Buyer " + (i + 1));
            msg.setBuyerEmail("buyer" + (i + 1) + "@example.com");
            msg.setPlatformOrderNo("ORD-" + shopId + "-" + (1000 + i));
            msg.setSubject(MESSAGE_SUBJECTS[i]);
            msg.setContent("This is a sample message about " + MESSAGE_SUBJECTS[i]
                    + " from platform " + platform);
            msg.setDirection("IN");
            msg.setStatus("UNREAD");
            msg.setIsUrgent(i == 0);
            msg.setReceiveTime(LocalDateTime.now().minusMinutes(30 + i * 10L));
            msg.setCreateTime(LocalDateTime.now());
            msg.setUpdateTime(LocalDateTime.now());
            list.add(msg);
        }
        return list;
    }

    static List<PlatformInventory> inventory(String platform, Long shopId) {
        // 与旧实现一致：非亚马逊平台用单一仓库标识，亚马逊用两个 FBA 仓
        String[] warehouses = "AMAZON".equals(platform)
                ? new String[]{"FBA-ON1", "FBA-LAX9"}
                : new String[]{platform + "-WH1"};
        Random random = new Random();
        List<PlatformInventory> list = new ArrayList<>();
        for (String sku : INVENTORY_SKUS) {
            for (String warehouse : warehouses) {
                PlatformInventory inv = new PlatformInventory();
                inv.setShopId(shopId);
                inv.setPlatform(platform);
                inv.setPlatformProductId(platform + "-" + sku);
                inv.setSku(sku);
                inv.setWarehouse(warehouse);
                inv.setAvailableQty(50 + random.nextInt(151));
                inv.setReservedQty(random.nextInt(21));
                inv.setInboundQty(random.nextInt(31));
                inv.setSnapshotTime(LocalDateTime.now());
                inv.setCreateTime(LocalDateTime.now());
                list.add(inv);
            }
        }
        return list;
    }
}
