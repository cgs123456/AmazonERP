package com.amz.auth;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AWS Signature V4 签名器，用于对 SP-API（execute-api）请求进行签名。
 * <p>
 * 签名流程：
 * <ol>
 *   <li>构造规范请求（Canonical Request）</li>
 *   <li>构造待签字符串（String to Sign）</li>
 *   <li>派生签名密钥（Signing Key）</li>
 *   <li>计算 HMAC-SHA256 得到最终签名</li>
 * </ol>
 * 这里使用 IAM 长期凭证（accessKey/secretKey），签名头为 host + x-amz-date，
 * LWA access_token 通过 x-amz-access-token 头透传，不参与签名。
 */
@Component
public class AwsSigV4Signer {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE_NAME = "execute-api";
    private static final String TERMINATOR = "aws4_request";

    /**
     * 对一次 HTTP 请求进行 Sig V4 签名，返回需要附加到请求上的头集合
     * （包含 x-amz-date 与 Authorization）。
     *
     * @param method               HTTP 方法（GET/POST/...）
     * @param host                 请求主机名（不含协议与端口），如 sellingpartnerapi-na.amazon.com
     * @param path                 请求路径，如 /orders/v0/orders
     * @param canonicalQueryString 规范查询串（参数名按字典序排序、URI 编码后以 & 拼接）
     * @param body                 请求体（GET 请求传空串）
     * @param accessKey            AWS Access Key ID
     * @param secretKey            AWS Secret Access Key
     * @param region               AWS 区域，如 us-east-1
     * @return 需附加到请求的头集合
     */
    public Map<String, String> sign(String method, String host, String path,
                                    String canonicalQueryString, String body,
                                    String accessKey, String secretKey, String region) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = now.format(TIME_FORMAT);
        String dateStamp = now.format(DATE_FORMAT);

        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-date";
        String payloadHash = sha256Hex(body == null ? "" : body);

        String canonicalRequest = createCanonicalRequest(method, path, canonicalQueryString,
                canonicalHeaders, signedHeaders, payloadHash);

        String credentialScope = dateStamp + "/" + region + "/" + SERVICE_NAME + "/" + TERMINATOR;
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, SERVICE_NAME);
        String signature = hmacSha256Hex(signingKey, stringToSign);

        String authorizationHeader = ALGORITHM + " "
                + "Credential=" + accessKey + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaders + ", "
                + "Signature=" + signature;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-amz-date", amzDate);
        headers.put("Authorization", authorizationHeader);
        return headers;
    }

    /**
     * 构造规范请求（Canonical Request）。
     */
    String createCanonicalRequest(String method, String path, String queryString,
                                  String canonicalHeaders, String signedHeaders, String payloadHash) {
        return method + "\n"
                + path + "\n"
                + queryString + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
    }

    /**
     * 派生签名密钥：kSecret -> kDate -> kRegion -> kService -> kSigning。
     */
    byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName) {
        byte[] kSecret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp);
        byte[] kRegion = hmacSha256(kDate, regionName);
        byte[] kService = hmacSha256(kRegion, serviceName);
        return hmacSha256(kService, TERMINATOR);
    }

    /**
     * 计算 HMAC-SHA256，返回原始字节数组。
     */
    byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    /**
     * 计算 HMAC-SHA256，返回十六进制字符串。
     */
    String hmacSha256Hex(byte[] key, String data) {
        return bytesToHex(hmacSha256(key, data));
    }

    /**
     * 计算 SHA-256，返回十六进制字符串。
     */
    String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256", e);
        }
    }

    /**
     * 字节数组转小写十六进制字符串。
     */
    String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
