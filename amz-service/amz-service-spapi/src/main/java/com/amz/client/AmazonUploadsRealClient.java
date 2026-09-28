package com.amz.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Amazon Uploads API 真实客户端。
 *
 * <p>只实现官方 {@code uploads_2020-11-01.json} 的
 * {@code createUploadDestinationForResource}，并在此之上提供“创建目的地 + PUT 上传”闭环。
 * 所有 SP-API 请求统一经 {@link SpApiGateway} 出站，复用 LWA、区域端点、主机白名单、
 * 限流与 fail-closed 错误处理；S3 PUT 使用响应返回的 URL 与 headers，绝不携带 LWA 或 AWS 签名。
 *
 * <p>典型资源路径：
 * <ul>
 *   <li>{@code /messaging/v1/orders/{amazonOrderId}/messages/legalDisclosure}</li>
 *   <li>{@code aplus/2020-11-01/contentDocuments}</li>
 * </ul>
 */
@Component
@Profile("!mock")
public class AmazonUploadsRealClient {

    static final String UPLOADS_PATH = "/uploads/2020-11-01/uploadDestinations/";
    private static final String OPERATION_ID = "uploads.createUploadDestinationForResource";
    private static final Pattern MD5_HEX = Pattern.compile("[0-9a-fA-F]{32}");

    private final SpApiGateway gateway;

    public AmazonUploadsRealClient(SpApiGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 创建上传目的地。调用方提供原始内容的 MD5 小写十六进制值。
     *
     * @return 官方响应 JSON（成功状态码必须为 201）
     */
    public JsonObject createUploadDestinationForResource(Long shopId, String marketplaceId,
                                                          String contentMD5Hex, String resource,
                                                          String contentType) {
        requirePositiveShopId(shopId);
        requireNonBlank(marketplaceId, "marketplaceId");
        String normalizedMd5 = normalizeMd5(contentMD5Hex);
        String normalizedResource = normalizeResource(resource);
        String normalizedContentType = normalizeContentType(contentType);

        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("contentMD5", normalizedMd5);
        query.put("marketplaceIds", marketplaceId);
        if (normalizedContentType != null) {
            query.put("contentType", normalizedContentType);
        }
        return gateway.callJsonWithStatus("POST", shop, OPERATION_ID,
                UPLOADS_PATH + normalizedResource,
                SpApiGateway.canonicalQuery(query), null, 201);
    }

    /**
     * 计算原始字节的 MD5、创建上传目的地、再 PUT 到 S3 预签名 URL。
     *
     * @return {@code uploadDestinationId}，可放入 Messaging 的 Attachment
     */
    public String createUploadDestinationAndUpload(Long shopId, String marketplaceId,
                                                   String resource, String contentType,
                                                   byte[] content) {
        byte[] requiredContent = requireContent(content);
        JsonObject response = createUploadDestinationForResource(shopId, marketplaceId,
                md5Hex(requiredContent), resource, contentType);
        JsonObject payload = requireObject(response, "payload");
        String destinationId = requireString(payload, "uploadDestinationId");
        String url = requireString(payload, "url");
        Map<String, String> headers = parseHeaders(payload.get("headers"));

        gateway.uploadBytes(url, headers, normalizeContentType(contentType), requiredContent);
        return destinationId;
    }

    /**
     * 规范化 resource：允许一个可选前导斜杠，保留内部斜杠，逐段进行 RFC 3986 编码。
     * 空白、控制字符、查询/片段、反斜杠、空段与路径穿越在发请求前拒绝。
     */
    private static String normalizeResource(String resource) {
        requireNonBlank(resource, "resource");
        String value = resource.startsWith("/") ? resource.substring(1) : resource;
        if (value.isEmpty()) {
            throw new IllegalArgumentException("resource must contain a path");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)
                    || ch == '?' || ch == '#' || ch == '\\') {
                throw new IllegalArgumentException(
                        "resource contains an illegal character at index " + i + ": " + ch);
            }
        }
        String[] segments = value.split("/", -1);
        StringBuilder encoded = new StringBuilder(value.length());
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "resource contains an empty or traversal path segment");
            }
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(encodePathSegment(segment));
        }
        return encoded.toString();
    }

    private static String encodePathSegment(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length());
        for (byte raw : segment.getBytes(StandardCharsets.UTF_8)) {
            int value = raw & 0xff;
            if ((value >= 'a' && value <= 'z')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '.' || value == '_' || value == '~') {
                encoded.append((char) value);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((value >> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(value & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static String normalizeMd5(String value) {
        if (value == null || !MD5_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "contentMD5 must be a 32-character hexadecimal MD5 string");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        if (contentType.indexOf('\r') >= 0 || contentType.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("contentType contains an illegal newline");
        }
        return contentType;
    }

    private static byte[] requireContent(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must not be null or empty");
        }
        return content;
    }

    private static String md5Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 digest is unavailable", e);
        }
    }

    private static Map<String, String> parseHeaders(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            throw new IllegalStateException("Uploads payload.headers must be an object");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isBlank()) {
                throw new IllegalStateException(
                        "Uploads payload.headers[" + entry.getKey() + "] must be a non-blank string");
            }
            headers.put(entry.getKey(), value.getAsString());
        }
        return headers;
    }

    private static JsonObject requireObject(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException("Uploads response missing object field: " + field);
        }
        return value.getAsJsonObject();
    }

    private static String requireString(JsonObject parent, String field) {
        JsonElement value = parent.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalStateException(
                    "Uploads response missing non-blank string field: " + field);
        }
        return value.getAsString();
    }

    private static void requirePositiveShopId(Long shopId) {
        if (shopId == null || shopId <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
