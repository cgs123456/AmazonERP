package com.amz.client;

import com.amz.credential.PlatformCredential;
import com.amz.credential.PlatformCredentialService;
import com.amz.http.ResilientHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 多平台客户端抽象基类。
 * <p>
 * Temu / TikTok Shop / Shein 三个平台客户端代码重复率 90%+，故抽取公共逻辑：
 * <ul>
 *   <li>{@link #mask(String)} 凭证脱敏</li>
 *   <li>{@link #isBlank(String)} 空值判断</li>
 *   <li>{@link #md5Hex(String)} / {@link #hmacSha256Hex(String, String)} 签名工具（Shein/Temu 用 MD5，TikTok 用 HMAC-SHA256）</li>
 *   <li>{@link #httpPost(String, Map, String)} / {@link #httpGet(String, Map)} HTTP 工具</li>
 *   <li>{@link #objectMapper} JSON 解析（Jackson）</li>
 * </ul>
 * 子类通过 {@link #getPlatform()} 声明平台标识，用于日志、指标打标与熔断隔离。
 * <p>
 * <b>出站通道：</b>所有 HTTP 调用统一走 {@link ResilientHttpClient}
 * （超时 3s/10s + 指数退避重试 + 按平台维度熔断 + Micrometer 指标）。
 * 改造前此处为裸 {@code new RestTemplate()}，无超时会在平台侧挂起时耗尽调用方线程。
 */
@Slf4j
public abstract class AbstractPlatformClient {

    /** 平台标识：TEMU / TIKTOK / SHEIN */
    protected static final String PLATFORM_TEMU = "TEMU";
    protected static final String PLATFORM_TIKTOK = "TIKTOK";
    protected static final String PLATFORM_SHEIN = "SHEIN";

    /** JSON 解析器（子类共用）。 */
    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 统一出站 HTTP 通道（子类共用）。
     * <p>
     * 采用字段注入而非构造器注入：子类（Temu/TikTok/Shein 各 Real/Mock 共 6 个）
     * 均使用 {@code @Value} 字段配置且无自定义构造器，改构造器注入需同时修改 6 个子类，
     * 收益与改动量不成比例。{@code required = false} 保证脱离 Spring 容器的
     * 纯单元测试可直接实例化子类（Mock 客户端不发起 HTTP）。
     */
    @Autowired(required = false)
    protected ResilientHttpClient http;

    /**
     * 多租户平台凭证解析服务（按 (shopId, platform) 从 amz_platform_account 解析并解密）。
     * 软依赖：脱离 Spring 容器（纯单测）时为 null，此时 {@link #cred(Long)} 返回空凭证，
     * 客户端据此触发既有的诚实失败逻辑，行为与原全局 @Value 空默认值一致。
     */
    @Autowired(required = false)
    protected PlatformCredentialService credentialService;

    /**
     * 子类返回各自平台标识，用于日志与异常定位。
     */
    protected abstract String getPlatform();

    /**
     * 解析当前店铺、当前平台的凭证（多租户，来源 amz_platform_account）。
     * <p>
     * 取代原先各子类持有的 {@code @Value("${platform.*}")} 全局字段。
     * credentialService 为 null 时返回空凭证，由调用方决定诚实失败或模拟降级。
     *
     * @param shopId 店铺 ID；部分无 shopId 上下文的调用（如 markShipped）传 null，
     *               此时服务回退到该平台的全局默认账号
     */
    protected PlatformCredential cred(Long shopId) {
        if (credentialService == null) {
            return PlatformCredential.empty();
        }
        return credentialService.resolve(shopId, getPlatform());
    }

    /**
     * 凭证脱敏：保留前 2 + 后 2 位，中间用 **** 替代。
     * 用于日志输出 appKey/appSecret，避免明文泄露。
     */
    protected String mask(String key) {
        if (key == null || key.length() < 4) {
            return "****";
        }
        return key.substring(0, 2) + "****" + key.substring(key.length() - 2);
    }

    /**
     * 空值判断（null 或仅空白）。
     */
    protected boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Jackson 文本节点取值：缺失/null/空串一律返回 null（B3：三客户端各存一份私有拷贝，现收敛）。
     */
    protected static String asTextOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isEmpty()) {
            return null;
        }
        return node.asText();
    }

    /**
     * 判断节点是否“缺席”（B3：Jackson 数值/文本节点的 {@code isEmpty()} 恒为 true，
     * 不能用于值回退判断——Temu sku_count 与 Shein payAmount 曾因此静默丢数据）。
     * <p>
     * 规则：null / Missing / Null → 缺席；容器按长度；文本按空串；数字/布尔视为存在。
     */
    protected static boolean isAbsent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return true;
        }
        if (node.isContainerNode()) {
            return node.isEmpty();
        }
        if (node.isTextual()) {
            return node.asText().isEmpty();
        }
        return false;
    }

    /**
     * 取首个存在的节点（字段别名回退，如 payAmount/orderAmount）。
     */
    protected static JsonNode firstPresent(JsonNode primary, JsonNode fallback) {
        return isAbsent(primary) ? fallback : primary;
    }

    /**
     * 数量节点转 Integer：缺失/null/非法文本一律返回 null，不臆测。
     * <p>
     * 此前各客户端用 {@code asInt(1)}，文本型/非法数量被静默记为 1，
     * 多商品订单的数量直接算错。
     */
    protected static Integer parseIntOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.valueOf(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 构造 URL 查询串（B3：Shein/Temu 各存一份私有拷贝，现收敛）。
     * <p>
     * 参数值必须 URL 编码：时间戳等含空格/保留字符的值不编码会破坏请求行；
     * 签名在编码前的原始 Map 上计算，不受此处影响。
     */
    protected static String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(),
                            StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * MD5 十六进制签名（Shein / Temu 平台鉴权使用）。
     */
    protected String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 计算失败", e);
        }
    }

    /**
     * HMAC-SHA256 十六进制签名（TikTok Shop 平台鉴权使用）。
     */
    protected String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 计算失败", e);
        }
    }

    /**
     * 发起 POST 请求并返回响应体字符串（走弹性通道，target = 平台标识）。
     * <p>
     * 注意：平台下单等非幂等写接口在网络异常重试下存在重复提交风险，
     * 需由调用方结合平台侧幂等键（如 out_order_no）保证幂等。
     */
    protected String httpPost(String url, Map<String, String> headers, String body) {
        return requireHttp().post(getPlatform(), url, headers, body);
    }

    /**
     * 发起 GET 请求并返回响应体字符串（走弹性通道，target = 平台标识）。
     */
    protected String httpGet(String url, Map<String, String> headers) {
        return requireHttp().get(getPlatform(), url, headers);
    }

    /**
     * 校验出站通道已注入。Real 客户端在 Spring 容器中运行必然注入成功；
     * 若为 null 说明被脱离容器直接实例化，明确报错优于隐式 NPE。
     */
    private ResilientHttpClient requireHttp() {
        if (http == null) {
            throw new IllegalStateException("ResilientHttpClient 未注入，平台 "
                    + getPlatform() + " 无法发起 HTTP 调用（请通过 Spring 容器获取该客户端）");
        }
        return http;
    }
}
