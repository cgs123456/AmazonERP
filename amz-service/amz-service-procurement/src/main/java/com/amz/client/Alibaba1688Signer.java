package com.amz.client;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1688 开放平台（AliOpen / TOP 风格）请求签名器。
 * <p>
 * 签名算法（与淘宝/1688 开放平台一致）：
 * <ol>
 *   <li>收集所有待签参数（系统参数 + 业务参数，<b>不含 sign 本身</b>）。</li>
 *   <li>按参数名 ASCII 升序排序。</li>
 *   <li>拼接为 {@code 参数名 + 参数值}（无分隔符，非 {@code key=value}）。</li>
 *   <li>前后各包裹一次 appSecret：{@code secret + 拼接串 + secret}。</li>
 *   <li>按 {@code sign_method} 做摘要：md5 → 32 位十六进制；sha1 → 40 位十六进制；结果转大写。</li>
 * </ol>
 * 该拼接方式刻意与仓库内 Temu / Shein 的 MD5 签名保持同构（均为 {@code secret+排序kv+secret}），
 * 便于统一认知与排错。
 * <p>
 * ⚠️ <b>校准状态：未校准。</b> 本实现为基于公开文档的 best-effort，尚未经 1688 官方沙箱验证。
 * 已知需沙箱确认的两点：(1) {@code sign_method=sha1} 时 1688 实际采用「整体 SHA1」还是
 * 「HMAC-SHA1(base64)」；(2) 部分历史接口要求参数值先做 URL-encode 再拼接。
 * 接入沙箱后须以官方示例固化（见 {@code Alibaba1688SignerTest}）。
 */
@Slf4j
public final class Alibaba1688Signer {

    private Alibaba1688Signer() {
    }

    /**
     * 生成 1688 请求签名。
     *
     * @param params    待签参数（不含 sign）
     * @param appSecret 应用密钥
     * @param signMethod "md5"（默认）或 "sha1"
     * @return 大写十六进制签名串
     */
    public static String sign(Map<String, String> params, String appSecret, String signMethod) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder concat = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            concat.append(e.getKey()).append(e.getValue() == null ? "" : e.getValue());
        }
        String signContent = appSecret + concat + appSecret;
        String method = (signMethod == null ? "md5" : signMethod).toLowerCase();
        switch (method) {
            case "sha1":
                return sha1Hex(signContent).toUpperCase();
            case "md5":
            default:
                return md5Hex(signContent).toUpperCase();
        }
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("1688 MD5 签名计算失败", e);
        }
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("1688 SHA1 签名计算失败", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
