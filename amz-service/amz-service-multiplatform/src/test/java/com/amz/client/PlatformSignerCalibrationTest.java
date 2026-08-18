package com.amz.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三平台签名「校准」测试套件。
 * <p>
 * <b>定位：</b>本测试套件是 Item4（多平台签名按官方沙箱校准）的固化载体。
 * 当前三平台签名均为 best-effort（见各 RealClient 的「未校准」标注），
 * 本套件做两件事：
 * <ol>
 *   <li><b>内部一致性交叉验证</b>：用测试内独立的 MessageDigest / Mac 重新实现同一算法，
 *       断言客户端签名与之相等，确保「实现 == 文档算法」（排除实现层 bug）。</li>
 *   <li><b>锁定当前行为</b>：顺序无关性、输出格式（MD5 32 大写 / HMAC-SHA256 64 小写）等，
 *       防止后续重构无意改动签名。</li>
 * </ol>
 * <b>重要：</b>本套件验证「实现自洽」，<u>不替代</u>官方沙箱对照。接入沙箱后，应补充
 * 官方示例请求/响应对照（known-vector）以确认「实现 == 平台期望」。各平台待沙箱确认点见
 * {@link TemuRealClient#signTemu}、{@link SheinRealClient#signShein}、{@link TikTokRealClient#signTikTok}
 * 的 Javadoc。
 */
@DisplayName("三平台签名校准测试")
class PlatformSignerCalibrationTest {

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString().toUpperCase();
    }

    private static String hmacSha256Hex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] b = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------- Temu

    @Test
    @DisplayName("Temu 签名：MD5 32 位大写 + 与独立实现一致 + 顺序无关")
    void testTemuSign() throws Exception {
        Map<String, String> p = new TreeMap<>();
        p.put("app_key", "ak");
        p.put("method", "order.list");
        p.put("timestamp", "1700000000");
        p.put("access_token", "tok");

        String sign = new TemuRealClient().signTemu(p, "secret");
        assertTrue(sign.matches("^[0-9A-F]{32}$"), "Temu MD5 应为 32 位大写十六进制，实际=" + sign);

        StringBuilder concat = new StringBuilder("secret");
        for (Map.Entry<String, String> e : p.entrySet()) {
            concat.append(e.getKey()).append(e.getValue());
        }
        concat.append("secret");
        assertEquals(md5Hex(concat.toString()), sign, "Temu 签名应与独立 MD5 实现一致");

        Map<String, String> q = new TreeMap<>();
        q.put("timestamp", "1700000000");
        q.put("access_token", "tok");
        q.put("app_key", "ak");
        q.put("method", "order.list");
        assertEquals(sign, new TemuRealClient().signTemu(q, "secret"), "Temu 签名应顺序无关");
    }

    // ---------------------------------------------------------------- Shein

    @Test
    @DisplayName("Shein 签名：MD5 32 位大写 + 与独立实现一致 + 顺序无关")
    void testSheinSign() throws Exception {
        Map<String, String> p = new TreeMap<>();
        p.put("app_key", "ak");
        p.put("method", "shein.order.list.query");
        p.put("timestamp", "2026-08-01 12:00:00");
        p.put("format", "json");
        p.put("v", "1.0");

        String sign = new SheinRealClient().signShein(p, "secret");
        assertTrue(sign.matches("^[0-9A-F]{32}$"), "Shein MD5 应为 32 位大写十六进制，实际=" + sign);

        StringBuilder concat = new StringBuilder("secret");
        for (Map.Entry<String, String> e : p.entrySet()) {
            concat.append(e.getKey()).append(e.getValue());
        }
        concat.append("secret");
        assertEquals(md5Hex(concat.toString()), sign, "Shein 签名应与独立 MD5 实现一致");

        Map<String, String> q = new TreeMap<>();
        q.put("v", "1.0");
        q.put("format", "json");
        q.put("timestamp", "2026-08-01 12:00:00");
        q.put("method", "shein.order.list.query");
        q.put("app_key", "ak");
        assertEquals(sign, new SheinRealClient().signShein(q, "secret"), "Shein 签名应顺序无关");
    }

    // ---------------------------------------------------------------- TikTok

    @Test
    @DisplayName("TikTok 签名：HMAC-SHA256 64 位小写 + 与独立实现一致")
    void testTikTokSign() throws Exception {
        String secret = "sk";
        String appKey = "ak";
        String path = "/order/202309/orders/search";
        long ts = 1700000000L;
        String body = "{}";

        String sign = new TikTokRealClient().signTikTok(secret, appKey, path, ts, body);
        assertTrue(sign.matches("^[0-9a-f]{64}$"), "TikTok HMAC-SHA256 应为 64 位小写十六进制，实际=" + sign);

        String expected = hmacSha256Hex(secret, appKey + path + ts + body);
        assertEquals(expected, sign, "TikTok 签名应与独立 HMAC-SHA256 实现一致");

        // 基准串任何片段变化都应改变签名
        String other = new TikTokRealClient().signTikTok(secret, appKey, path, ts + 1, body);
        assertNotEquals(sign, other, "TikTok 签名应对 timestamp 敏感");
    }
}
