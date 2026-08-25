package com.amz.client;

import com.amz.credential.PlatformCredential;
import com.amz.model.UnifiedOrder;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TikTok Shop 开放平台真实客户端。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 鉴权方式遵循 TikTok Shop API：HMAC-SHA256 签名（appSecret 为 key，
 * appKey + path + timestamp + body 为待签数据），请求头透传
 * x-tts-access-token / x-tts-app-key / x-tts-timestamp / x-tts-signature。
 * <p>
 * 与 TikTokMockClient 的区别：本实现真实发起 TikTok Shop API 请求，
 * 不再「打 warn 日志 + 返回空列表/默认 false」假降级。
 * 当 appKey / accessToken 未配置时抛出 {@link IllegalStateException}（诚实失败），
 * 由调用方决定重试或告警，而非静默吞掉导致「假成功」。
 */
@Slf4j
@Component
@Profile("!mock")
public class TikTokRealClient extends AbstractPlatformClient implements TikTokClient {

    @Override
    protected String getPlatform() {
        return PLATFORM_TIKTOK;
    }

    @Override
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        PlatformCredential c = cred(shopId);
        if (isBlank(c.getAppKey()) || isBlank(c.getAccessToken())) {
            throw new IllegalStateException("TikTok 凭证未配置（appKey/accessToken），无法拉取订单 shopId=" + shopId);
        }
        String path = "/order/202309/orders/search";
        long ts = Instant.now().getEpochSecond();
        String body = "{}";
        String signature = signTikTok(c.getAppSecret(), c.getAppKey(), path, ts, body);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-tts-access-token", c.getAccessToken());
        headers.put("x-tts-app-key", c.getAppKey());
        headers.put("x-tts-timestamp", String.valueOf(ts));
        headers.put("x-tts-signature", signature);
        try {
            String resp = httpPost(c.getApiBase() + path, headers, body);
            return parseOrders(resp, shopId);
        } catch (Exception e) {
            log.error("TikTok fetchRecentOrders failed shopId={} appKey={}", shopId, mask(c.getAppKey()), e);
            throw new RuntimeException("TikTok fetchRecentOrders failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        // markShipped 仅持有平台订单号，无 shopId 上下文，回退到该平台全局默认账号
        PlatformCredential c = cred(null);
        if (isBlank(c.getAppKey()) || isBlank(c.getAccessToken()) || isBlank(platformOrderNo)) {
            throw new IllegalStateException("TikTok 凭证或订单号缺失，无法回传发货 orderNo=" + platformOrderNo);
        }
        String path = "/fulfillment/202309/orders/" + platformOrderNo + "/ship";
        long ts = Instant.now().getEpochSecond();
        String body = "{\"tracking_number\":\"" + trackingNo + "\",\"shipping_provider_id\":\"OTHER\"}";
        String signature = signTikTok(c.getAppSecret(), c.getAppKey(), path, ts, body);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-tts-access-token", c.getAccessToken());
        headers.put("x-tts-app-key", c.getAppKey());
        headers.put("x-tts-timestamp", String.valueOf(ts));
        headers.put("x-tts-signature", signature);
        try {
            String resp = httpPost(c.getApiBase() + path, headers, body);
            JsonNode root = objectMapper.readTree(resp);
            boolean ok = root.path("code").asInt() == 0;
            log.info("TikTok markShipped orderNo={} trackingNo={} ok={}", platformOrderNo, trackingNo, ok);
            return ok;
        } catch (Exception e) {
            log.error("TikTok markShipped failed orderNo={}", platformOrderNo, e);
            throw new RuntimeException("TikTok markShipped failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 TikTok orders/search 响应，归一化为 UnifiedOrder 列表。
     * 字段缺失时跳过对应赋值，避免 NPE；解析到的订单数量由日志输出供排查。
     */
    private List<UnifiedOrder> parseOrders(String resp, Long shopId) throws Exception {
        List<UnifiedOrder> list = new ArrayList<>();
        JsonNode root = objectMapper.readTree(resp);
        if (root.path("code").asInt() != 0) {
            log.warn("TikTok orders/search 非成功响应 code={} msg={}",
                    root.path("code").asText(), root.path("message").asText());
            return list;
        }
        JsonNode orders = root.path("data").path("orders");
        if (orders.isArray()) {
            for (JsonNode o : orders) {
                UnifiedOrder uo = new UnifiedOrder();
                uo.setPlatform(PLATFORM_TIKTOK);
                uo.setShopId(shopId);
                uo.setPlatformOrderNo(asTextOrNull(o.path("order_id")));
                uo.setStatus(mapStatus(asTextOrNull(o.path("status"))));
                uo.setBuyerNickname(asTextOrNull(o.path("buyer").path("username")));
                JsonNode addr = o.path("recipient_address");
                uo.setShipCountry(asTextOrNull(addr.path("country")));
                JsonNode products = o.path("product_list");
                if (products.isArray() && products.size() > 0) {
                    JsonNode p = products.get(0);
                    uo.setSku(asTextOrNull(p.path("sku_id")));
                    uo.setProductName(asTextOrNull(p.path("product_name")));
                    if (!p.path("quantity").isMissingNode() && !p.path("quantity").isNull()) {
                        uo.setQuantity(p.path("quantity").asInt(1));
                    }
                }
                JsonNode pay = o.path("payment");
                String amt = asTextOrNull(pay.path("amount"));
                if (amt != null) {
                    try {
                        uo.setOriginalAmount(new BigDecimal(amt));
                    } catch (NumberFormatException e) {
                        log.debug("TikTok 订单金额解析失败 orderId={} amount={}", uo.getPlatformOrderNo(), amt);
                    }
                }
                uo.setCurrency(asTextOrNull(pay.path("currency")));
                long create = o.path("create_time").asLong(0);
                if (create > 0) {
                    uo.setOrderCreateTime(Instant.ofEpochSecond(create).toString());
                }
                list.add(uo);
            }
        }
        log.info("TikTok fetchRecentOrders shopId={} count={}", shopId, list.size());
        return list;
    }

    /**
     * TikTok Shop 订单状态 -> 统一订单状态映射。
     */
    private String mapStatus(String tiktokStatus) {
        if (tiktokStatus == null) {
            return "PAID";
        }
        return switch (tiktokStatus) {
            case "UNPAID" -> "UNPAID";
            case "AWAITING_SHIPMENT", "AWAITING_COLLECTION" -> "PAID";
            case "IN_TRANSIT" -> "SHIPPED";
            case "DELIVERED" -> "DELIVERED";
            case "COMPLETED" -> "COMPLETED";
            case "CANCELLED" -> "CANCELED";
            default -> "PAID";
        };
    }

    /**
     * TikTok Shop 签名：HMAC-SHA256(appSecret, appKey + path + timestamp + body)。
     * <p>
     * ⚠️ <b>未校准。</b> 当前为基于公开文档的 best-effort，尚未经 TikTok 官方沙箱验证。
     * 已知需沙箱确认点：(1) 基准串拼接顺序（官方部分版本为 app_key + timestamp + path + body，
     * 或含 shop_cipher / shop_id）；(2) body 是否取原始 JSON 原文；(3) timestamp 单位（秒）。
     * 校准后以 {@code PlatformSignerCalibrationTest} 固化。
     */
    String signTikTok(String secret, String appKey, String path, long ts, String body) {
        return hmacSha256Hex(secret, appKey + path + ts + body);
    }

    private String asTextOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isEmpty()) {
            return null;
        }
        return node.asText();
    }
}
