package com.amz.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1688 签名器单元测试。
 * <p>
 * 覆盖：MD5/SHA1 输出格式（大写十六进制）、顺序无关性、确定性，
 * 并通过独立实现的 MessageDigest 交叉验证「secret+拼接串+secret」摘要公式的正确性。
 * <p>
 * 说明：本测试锁定的是「签名实现与公开文档算法一致」，并不替代 1688 官方沙箱校准
 * （见 {@link Alibaba1688Signer} 的「未校准」标注）。
 */
@DisplayName("1688 签名器测试")
class Alibaba1688SignerTest {

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String sha1Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    @Test
    @DisplayName("MD5 签名输出为 32 位大写十六进制")
    void testMd5Format() {
        Map<String, String> params = new HashMap<>();
        params.put("app_key", "ak");
        params.put("method", "alibaba.trade.get");
        params.put("timestamp", "2026-08-01 12:00:00");
        String sign = Alibaba1688Signer.sign(params, "secret", "md5");
        assertTrue(sign.matches("^[0-9A-F]{32}$"), "MD5 应为 32 位大写十六进制，实际=" + sign);
    }

    @Test
    @DisplayName("SHA1 签名输出为 40 位大写十六进制")
    void testSha1Format() {
        Map<String, String> params = new HashMap<>();
        params.put("app_key", "ak");
        params.put("method", "alibaba.trade.get");
        String sign = Alibaba1688Signer.sign(params, "secret", "sha1");
        assertTrue(sign.matches("^[0-9A-F]{40}$"), "SHA1 应为 40 位大写十六进制，实际=" + sign);
    }

    @Test
    @DisplayName("签名与插入顺序无关（TreeMap 排序后一致）")
    void testOrderIndependent() {
        Map<String, String> a = new TreeMap<>();
        a.put("method", "m1");
        a.put("app_key", "ak");
        a.put("timestamp", "t");
        Map<String, String> b = new TreeMap<>();
        b.put("timestamp", "t");
        b.put("method", "m1");
        b.put("app_key", "ak");
        assertEquals(Alibaba1688Signer.sign(a, "s", "md5"),
                Alibaba1688Signer.sign(b, "s", "md5"), "不同插入顺序应得到相同签名");
    }

    @Test
    @DisplayName("相同输入产生相同签名（确定性）")
    void testDeterministic() {
        Map<String, String> a = new TreeMap<>();
        a.put("method", "m1");
        a.put("app_key", "ak");
        String s1 = Alibaba1688Signer.sign(a, "s", "md5");
        String s2 = Alibaba1688Signer.sign(a, "s", "md5");
        assertEquals(s1, s2);
    }

    @Test
    @DisplayName("不同输入产生不同签名")
    void testSensitiveToInput() {
        Map<String, String> a = new TreeMap<>();
        a.put("method", "m1");
        a.put("app_key", "ak");
        Map<String, String> b = new TreeMap<>();
        b.put("method", "m2");
        b.put("app_key", "ak");
        assertNotEquals(Alibaba1688Signer.sign(a, "s", "md5"),
                Alibaba1688Signer.sign(b, "s", "md5"));
    }

    @Test
    @DisplayName("MD5 与独立 MessageDigest 实现交叉验证（secret+拼接+secret 公式）")
    void testMd5MatchesReference() throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("method", "alibaba.trade.get");
        params.put("app_key", "myAppKey");
        params.put("timestamp", "2026-08-01 12:00:00");
        params.put("format", "json");

        StringBuilder concat = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            concat.append(e.getKey()).append(e.getValue());
        }
        String expected = md5Hex("secret" + concat + "secret").toUpperCase();

        assertEquals(expected, Alibaba1688Signer.sign(params, "secret", "md5"));
    }

    @Test
    @DisplayName("SHA1 与独立实现交叉验证")
    void testSha1MatchesReference() throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("method", "alibaba.trade.get");
        params.put("app_key", "myAppKey");
        StringBuilder concat = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            concat.append(e.getKey()).append(e.getValue());
        }
        String expected = sha1Hex("secret" + concat + "secret").toUpperCase();
        assertEquals(expected, Alibaba1688Signer.sign(params, "secret", "sha1"));
    }
}
