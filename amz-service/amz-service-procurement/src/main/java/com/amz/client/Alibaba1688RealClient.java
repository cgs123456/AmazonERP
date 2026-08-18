package com.amz.client;

import com.amz.http.ResilientHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1688 开放平台真实 API 客户端（非 mock 环境生效）。
 * <p>
 * 实现内容：
 * <ul>
 *   <li>HMAC/MD5 签名：走 {@link Alibaba1688Signer}（TOP 风格 {@code secret+排序kv+secret}）。</li>
 *   <li>access_token 管理：{@link Alibaba1688TokenManager}（Redis 缓存 + refresh_token 刷新）。</li>
 *   <li>真实调用：{@code alibaba.trade.create} / {@code alibaba.trade.get} / {@code alibaba.trade.close}
 *       及物流单号查询，统一经 {@link ResilientHttpClient}（target=1688，超时/重试/熔断/指标）。</li>
 * </ul>
 * 凭证缺失（appKey/appSecret）时所有方法抛出 {@link IllegalStateException}（诚实失败），
 * 不再返回 {@code 1688_MOCK_} 占位订单号，避免上游误将假订单写入采购单。
 * <p>
 * ⚠️ 业务载荷说明：当前接口仅提供 offerId/quantity/unitPrice，而 1688
 * {@code alibaba.trade.create} 还需收货地址（addressParam）与明细（cargoParamList）。
 * 这些字段由采购单补全后传入（见后续 Item1 接入），本实现先按最小字段发起，
 * 若平台返回缺参错误会如实抛出并打日志，便于联调补齐。
 * <p>
 * ⚠️ 签名 / token 端点尚未经 1688 官方沙箱校准，详见 {@link Alibaba1688Signer} 与
 * {@link Alibaba1688TokenManager} 的「未校准」标注。
 */
@Slf4j
@Component
@Profile("!mock")
public class Alibaba1688RealClient implements Alibaba1688Client {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${alibaba.app-key:}")
    private String appKey;

    @Value("${alibaba.app-secret:}")
    private String appSecret;

    @Value("${alibaba.gateway:https://gw.open.1688.com/openapi}")
    private String gateway;

    @Value("${alibaba.refresh-token:}")
    private String refreshToken;

    @Autowired(required = false)
    private ResilientHttpClient http;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private Alibaba1688TokenManager tokenManager;

    /**
     * TokenManager 延迟初始化：仅在首次需要 access_token 时构建，
     * 避免构造期对 http / redisTemplate 注入状态做硬校验（纯单测可注入 mock）。
     */
    private Alibaba1688TokenManager tokenManager() {
        if (tokenManager == null) {
            tokenManager = new Alibaba1688TokenManager(
                    http, redisTemplate, appKey, appSecret, refreshToken, gateway);
        }
        return tokenManager;
    }

    @Override
    public String createOrder(String offerId, Integer quantity, BigDecimal unitPrice) {
        requireConfigured();
        TreeMap<String, String> biz = new TreeMap<>();
        biz.put("offerId", str(offerId));
        biz.put("quantity", str(quantity));
        biz.put("unitPrice", unitPrice == null ? "" : unitPrice.toPlainString());
        TreeMap<String, String> params = signedParams("alibaba.trade.create", biz, true);
        try {
            String resp = postForm(params);
            JsonNode root = objectMapper.readTree(resp);
            throwIfFailed(root);
            String orderId = extractText(root,
                    "result.orderId", "result.orderCode",
                    "alibaba_trade_create_response.result.orderId");
            if (orderId == null) {
                throw new IllegalStateException("1688 下单成功但未返回订单号 resp=" + resp);
            }
            log.info("1688 采购下单成功 offerId={} quantity={} → alibabaOrderNo={}",
                    offerId, quantity, orderId);
            return orderId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("1688 采购下单失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String queryOrderStatus(String alibabaOrderNo) {
        requireConfigured();
        TreeMap<String, String> biz = new TreeMap<>();
        biz.put("orderId", str(alibabaOrderNo));
        TreeMap<String, String> params = signedParams("alibaba.trade.get", biz, true);
        try {
            String resp = postForm(params);
            JsonNode root = objectMapper.readTree(resp);
            throwIfFailed(root);
            String raw = extractText(root,
                    "result.orderStatus", "result.status",
                    "alibaba_trade_get_response.result.orderStatus");
            String mapped = mapStatus(raw);
            log.info("1688 订单状态查询 alibabaOrderNo={} raw={} → {}", alibabaOrderNo, raw, mapped);
            return mapped;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("1688 订单状态查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String queryTrackingNo(String alibabaOrderNo) {
        requireConfigured();
        TreeMap<String, String> biz = new TreeMap<>();
        biz.put("orderId", str(alibabaOrderNo));
        TreeMap<String, String> params = signedParams("alibaba.trade.get", biz, true);
        try {
            String resp = postForm(params);
            JsonNode root = objectMapper.readTree(resp);
            throwIfFailed(root);
            String tracking = extractText(root,
                    "result.logisticsInfoList[0].logisticsNo",
                    "result.orderLogisticsList[0].logisticsNo",
                    "result.logisticsNo");
            log.info("1688 物流单号查询 alibabaOrderNo={} → trackingNo={}", alibabaOrderNo, tracking);
            return tracking;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("1688 物流单号查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean closeOrder(String alibabaOrderNo) {
        requireConfigured();
        TreeMap<String, String> biz = new TreeMap<>();
        biz.put("orderId", str(alibabaOrderNo));
        biz.put("reason", "买家取消采购");
        TreeMap<String, String> params = signedParams("alibaba.trade.close", biz, true);
        try {
            String resp = postForm(params);
            JsonNode root = objectMapper.readTree(resp);
            throwIfFailed(root);
            JsonNode success = root.path("result").path("success");
            boolean ok = !success.isMissingNode() ? success.asBoolean() : true;
            log.info("1688 关闭订单 alibabaOrderNo={} ok={}", alibabaOrderNo, ok);
            return ok;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("1688 关闭订单失败 alibabaOrderNo={}", alibabaOrderNo, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private TreeMap<String, String> signedParams(String method, Map<String, String> biz, boolean needToken) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("method", method);
        params.put("app_key", appKey);
        params.put("timestamp", LocalDateTime.now().format(TS_FMT));
        params.put("format", "json");
        params.put("v", "2.0");
        params.put("sign_method", "md5");
        if (needToken) {
            params.put("access_token", tokenManager().getAccessToken());
        }
        if (biz != null) {
            params.putAll(biz);
        }
        params.put("sign", Alibaba1688Signer.sign(params, appSecret, "md5"));
        return params;
    }

    private String postForm(TreeMap<String, String> params) {
        if (http == null) {
            throw new IllegalStateException(
                    "ResilientHttpClient 未注入，1688 无法发起 HTTP 调用（请通过 Spring 容器获取该客户端）");
        }
        String body = toFormBody(params);
        return http.post("1688", gateway,
                Map.of("Content-Type", "application/x-www-form-urlencoded"), body);
    }

    /**
     * 校验 1688 响应是否表示失败：TOP 风格以 {@code success=false} 或 {@code error_code} 表示错误。
     */
    private void throwIfFailed(JsonNode root) {
        JsonNode successNode = root.path("success");
        if (!successNode.isMissingNode() && !successNode.asBoolean()) {
            String err = root.path("error_message").asText(
                    root.path("sub_message").asText("未知错误"));
            throw new IllegalStateException("1688 API 返回失败：" + err);
        }
        JsonNode errorCode = root.path("error_code");
        if (!errorCode.isMissingNode() && !errorCode.asText("").isBlank()) {
            throw new IllegalStateException("1688 API 错误码：" + errorCode.asText()
                    + " msg=" + root.path("error_message").asText(""));
        }
    }

    /**
     * 将 1688 原始订单状态映射为统一状态码（与 {@link Alibaba1688Client} 接口契约一致）：
     * WAIT_PAY / WAIT_SEND / WAIT_RECEIVE / FINISHED / CLOSED。
     */
    private String mapStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "WAIT_SEND";
        }
        return switch (raw) {
            case "waitbuyerpay" -> "WAIT_PAY";
            case "waitsellersend" -> "WAIT_SEND";
            case "waitbuyerreceive" -> "WAIT_RECEIVE";
            case "success" -> "FINISHED";
            case "cancel", "terminated" -> "CLOSED";
            default -> "WAIT_SEND";
        };
    }

    private void requireConfigured() {
        if (isBlank(appKey) || isBlank(appSecret)) {
            throw new IllegalStateException(
                    "1688 凭证未配置（appKey/appSecret），无法发起 API 调用（请配置 alibaba.app-key/app-secret）");
        }
    }

    private String extractText(JsonNode root, String... candidatePaths) {
        for (String path : candidatePaths) {
            JsonNode node = root;
            boolean ok = true;
            for (String tok : path.split("\\.")) {
                String field = tok;
                Integer idx = null;
                int bracket = tok.indexOf('[');
                if (bracket >= 0) {
                    field = tok.substring(0, bracket);
                    idx = Integer.parseInt(tok.substring(bracket + 1, tok.indexOf(']')));
                }
                if (field != null && !field.isEmpty()) {
                    node = node.path(field);
                }
                if (node.isMissingNode() || node.isNull()) {
                    ok = false;
                    break;
                }
                if (idx != null) {
                    node = node.path(idx);
                    if (node.isMissingNode() || node.isNull()) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok && !node.isMissingNode() && !node.isNull()) {
                return node.asText();
            }
        }
        return null;
    }

    private static String toFormBody(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(e.getKey()).append("=").append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
