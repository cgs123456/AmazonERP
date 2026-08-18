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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Temu 开放平台真实客户端。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 鉴权方式遵循 Temu Seller Center Open API：MD5 签名（sign = MD5(secret + 排序后的 key+value 拼接 + secret)，大写），
 * 公共参数 app_key / timestamp / access_token / method 随请求透传。
 * <p>
 * 与 TemuMockClient 的区别：本实现真实发起 Temu 开放平台请求，
 * 不再「打 warn 日志 + 返回空列表/默认 false」假降级。
 * 当 appKey / appSecret / accessToken 未配置时抛出 {@link IllegalStateException}（诚实失败）。
 */
@Slf4j
@Component
@Profile("!mock")
public class TemuRealClient extends AbstractPlatformClient implements TemuClient {

    @Override
    protected String getPlatform() {
        return PLATFORM_TEMU;
    }

    @Override
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        PlatformCredential c = cred(shopId);
        if (isBlank(c.getAppKey()) || isBlank(c.getAppSecret()) || isBlank(c.getAccessToken())) {
            throw new IllegalStateException("Temu 凭证未配置（appKey/appSecret/accessToken），无法拉取订单 shopId=" + shopId);
        }
        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_key", c.getAppKey());
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        params.put("access_token", c.getAccessToken());
        params.put("method", "order.list");
        params.put("page", "1");
        params.put("page_size", "50");
        params.put("sign", signTemu(params, c.getAppSecret()));

        try {
            String resp = httpGet(c.getApiBase() + "/api/order/list?" + buildQuery(params), null);
            return parseOrders(resp, shopId);
        } catch (Exception e) {
            log.error("Temu fetchRecentOrders failed shopId={} appKey={}", shopId, mask(c.getAppKey()), e);
            throw new RuntimeException("Temu fetchRecentOrders failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        // markShipped 仅持有平台订单号，无 shopId 上下文，回退到该平台全局默认账号
        PlatformCredential c = cred(null);
        if (isBlank(c.getAppKey()) || isBlank(c.getAppSecret()) || isBlank(c.getAccessToken()) || isBlank(platformOrderNo)) {
            throw new IllegalStateException("Temu 凭证或订单号缺失，无法回传发货 orderNo=" + platformOrderNo);
        }
        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_key", c.getAppKey());
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        params.put("access_token", c.getAccessToken());
        params.put("method", "logistics.ship");
        params.put("order_sn", platformOrderNo);
        params.put("tracking_no", trackingNo);
        params.put("sign", signTemu(params, c.getAppSecret()));

        try {
            String resp = httpGet(c.getApiBase() + "/api/logistics/ship?" + buildQuery(params), null);
            JsonNode root = objectMapper.readTree(resp);
            boolean ok = root.path("code").asInt() == 0 || root.path("success").asBoolean(false);
            log.info("Temu markShipped orderNo={} trackingNo={} ok={}", platformOrderNo, trackingNo, ok);
            return ok;
        } catch (Exception e) {
            log.error("Temu markShipped failed orderNo={}", platformOrderNo, e);
            return false;
        }
    }

    /**
     * Temu 签名：MD5(secret + 排序后的 key+value 拼接 + secret)，结果大写。
     * <p>
     * ⚠️ <b>未校准。</b> 当前为基于公开文档的 best-effort，尚未经 Temu 官方沙箱验证。
     * 已知需沙箱确认点：(1) 待签串是否应包含 {@code sign_method}/{@code format} 等公共参数；
     * (2) 参数值是否需先 URL-encode。校准后以 {@code PlatformSignerCalibrationTest} 固化。
     */
    String signTemu(Map<String, String> params, String secret) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder(secret);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append(e.getValue());
        }
        sb.append(secret);
        return md5Hex(sb.toString()).toUpperCase();
    }

    private String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private List<UnifiedOrder> parseOrders(String resp, Long shopId) throws Exception {
        List<UnifiedOrder> list = new ArrayList<>();
        JsonNode root = objectMapper.readTree(resp);
        if (root.path("code").asInt() != 0 && !root.path("success").asBoolean(false)) {
            log.warn("Temu order list 非成功响应 code={} msg={}",
                    root.path("code").asText(), root.path("message").asText());
            return list;
        }
        JsonNode data = root.path("data");
        JsonNode orders = data.path("order_list");
        if (orders.isMissingNode() || orders.isNull()) {
            orders = data.path("list");
        }
        if (orders.isArray()) {
            for (JsonNode o : orders) {
                UnifiedOrder uo = new UnifiedOrder();
                uo.setPlatform(PLATFORM_TEMU);
                uo.setShopId(shopId);
                uo.setPlatformOrderNo(asTextOrNull(o.path("order_sn")));
                uo.setStatus("PAID");
                uo.setBuyerNickname(asTextOrNull(o.path("buyer_name")));
                uo.setShipCountry(asTextOrNull(o.path("country")));
                JsonNode items = o.path("sku_list").isEmpty() ? o.path("order_item_list") : o.path("sku_list");
                if (items.isArray() && items.size() > 0) {
                    JsonNode p = items.get(0);
                    uo.setSku(asTextOrNull(p.path("sku")));
                    uo.setProductName(asTextOrNull(p.path("product_name")));
                    JsonNode qty = p.path("sku_count").isEmpty() ? p.path("quantity") : p.path("sku_count");
                    if (!qty.isMissingNode() && !qty.isNull()) {
                        uo.setQuantity(qty.asInt(1));
                    }
                }
                JsonNode amt = o.path("pay_amount").isEmpty() ? o.path("order_amount") : o.path("pay_amount");
                String amtStr = asTextOrNull(amt);
                if (amtStr != null) {
                    try {
                        uo.setOriginalAmount(new BigDecimal(amtStr));
                    } catch (Exception ignore) {
                        log.debug("Temu 订单金额解析失败 orderNo={} amount={}", uo.getPlatformOrderNo(), amtStr);
                    }
                }
                uo.setCurrency(asTextOrNull(o.path("currency")));
                uo.setOrderCreateTime(asTextOrNull(o.path("created_time")));
                list.add(uo);
            }
        }
        log.info("Temu fetchRecentOrders shopId={} count={}", shopId, list.size());
        return list;
    }

    private String asTextOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isEmpty()) {
            return null;
        }
        return node.asText();
    }
}
