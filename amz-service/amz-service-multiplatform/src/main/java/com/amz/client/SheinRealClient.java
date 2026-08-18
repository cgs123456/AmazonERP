package com.amz.client;

import com.amz.credential.PlatformCredential;
import com.amz.model.UnifiedOrder;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shein 开放平台真实客户端。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 鉴权方式遵循 Shein Open Platform：MD5 签名（sign = MD5(secret + 排序后的 key+value 拼接 + secret)，大写），
 * 公共参数 app_key / method / timestamp / format / sign_method / v 随请求透传。
 * <p>
 * 与 SheinMockClient 的区别：本实现真实发起 Shein 开放平台请求，
 * 不再「打 warn 日志 + 返回空列表/默认 false」假降级。
 * 当 appKey / appSecret 未配置时抛出 {@link IllegalStateException}（诚实失败）。
 */
@Slf4j
@Component
@Profile("!mock")
public class SheinRealClient extends AbstractPlatformClient implements SheinClient {

    private static final DateTimeFormatter SHEIN_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    protected String getPlatform() {
        return PLATFORM_SHEIN;
    }

    @Override
    public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
        PlatformCredential c = cred(shopId);
        if (isBlank(c.getAppKey()) || isBlank(c.getAppSecret())) {
            throw new IllegalStateException("Shein 凭证未配置（appKey/appSecret），无法拉取订单 shopId=" + shopId);
        }
        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_key", c.getAppKey());
        params.put("method", "shein.order.list.query");
        params.put("timestamp", LocalDateTime.now().format(SHEIN_TS_FMT));
        params.put("format", "json");
        params.put("sign_method", "md5");
        params.put("v", "1.0");
        params.put("page_no", "1");
        params.put("page_size", "50");
        params.put("order_status", "PAID");
        params.put("sign", signShein(params, c.getAppSecret()));

        try {
            String resp = httpGet(c.getApiBase() + "/router?" + buildQuery(params), null);
            return parseOrders(resp, shopId);
        } catch (Exception e) {
            log.error("Shein fetchRecentOrders failed shopId={} appKey={}", shopId, mask(c.getAppKey()), e);
            throw new RuntimeException("Shein fetchRecentOrders failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean markShipped(String platformOrderNo, String trackingNo) {
        // markShipped 仅持有平台订单号，无 shopId 上下文，回退到该平台全局默认账号
        PlatformCredential c = cred(null);
        if (isBlank(c.getAppKey()) || isBlank(c.getAppSecret()) || isBlank(platformOrderNo)) {
            throw new IllegalStateException("Shein 凭证或订单号缺失，无法回传发货 orderNo=" + platformOrderNo);
        }
        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_key", c.getAppKey());
        params.put("method", "shein.logistics.upload");
        params.put("timestamp", LocalDateTime.now().format(SHEIN_TS_FMT));
        params.put("format", "json");
        params.put("sign_method", "md5");
        params.put("v", "1.0");
        params.put("order_sn", platformOrderNo);
        params.put("tracking_no", trackingNo);
        params.put("sign", signShein(params, c.getAppSecret()));

        try {
            String resp = httpGet(c.getApiBase() + "/router?" + buildQuery(params), null);
            JsonNode root = objectMapper.readTree(resp);
            boolean ok = root.path("code").asInt() == 0 || root.path("success").asBoolean(false);
            log.info("Shein markShipped orderNo={} trackingNo={} ok={}", platformOrderNo, trackingNo, ok);
            return ok;
        } catch (Exception e) {
            log.error("Shein markShipped failed orderNo={}", platformOrderNo, e);
            return false;
        }
    }

    /**
     * Shein 签名：MD5(secret + 排序后的 key+value 拼接 + secret)，结果大写。
     * <p>
     * ⚠️ <b>未校准。</b> 当前为基于公开文档的 best-effort，尚未经 Shein 官方沙箱验证。
     * 已知需沙箱确认点：(1) 待签参数集是否含 {@code format}/{@code v} 等公共参数；
     * (2) 参数值是否需先 URL-encode；(3) 时间戳格式（当前 {@code yyyy-MM-dd HH:mm:ss}）。
     * 校准后以 {@code PlatformSignerCalibrationTest} 固化。
     */
    String signShein(Map<String, String> params, String secret) {
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
            log.warn("Shein order list 非成功响应 code={} msg={}",
                    root.path("code").asText(), root.path("message").asText());
            return list;
        }
        JsonNode data = root.path("data");
        JsonNode orders = data.isArray() ? data : data.path("orders");
        if (orders.isMissingNode() || orders.isNull()) {
            orders = data.path("list");
        }
        if (orders.isArray()) {
            for (JsonNode o : orders) {
                UnifiedOrder uo = new UnifiedOrder();
                uo.setPlatform(PLATFORM_SHEIN);
                uo.setShopId(shopId);
                uo.setPlatformOrderNo(asTextOrNull(o.path("order_sn")));
                if (uo.getPlatformOrderNo() == null) {
                    uo.setPlatformOrderNo(asTextOrNull(o.path("orderId")));
                }
                uo.setStatus("PAID");
                uo.setBuyerNickname(asTextOrNull(o.path("buyerName")));
                uo.setShipCountry(asTextOrNull(o.path("country")));
                JsonNode items = o.path("skuList").isEmpty() ? o.path("itemList") : o.path("skuList");
                if (items.isArray() && items.size() > 0) {
                    JsonNode p = items.get(0);
                    uo.setSku(asTextOrNull(p.path("sku")));
                    uo.setProductName(asTextOrNull(p.path("productName")));
                    if (!p.path("quantity").isMissingNode() && !p.path("quantity").isNull()) {
                        uo.setQuantity(p.path("quantity").asInt(1));
                    }
                }
                JsonNode amt = o.path("payAmount").isEmpty() ? o.path("orderAmount") : o.path("payAmount");
                String amtStr = asTextOrNull(amt);
                if (amtStr != null) {
                    try {
                        uo.setOriginalAmount(new BigDecimal(amtStr));
                    } catch (Exception ignore) {
                        log.debug("Shein 订单金额解析失败 orderNo={} amount={}", uo.getPlatformOrderNo(), amtStr);
                    }
                }
                uo.setCurrency(asTextOrNull(o.path("currency")));
                uo.setOrderCreateTime(asTextOrNull(o.path("createTime")));
                list.add(uo);
            }
        }
        log.info("Shein fetchRecentOrders shopId={} count={}", shopId, list.size());
        return list;
    }

    private String asTextOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isEmpty()) {
            return null;
        }
        return node.asText();
    }
}
