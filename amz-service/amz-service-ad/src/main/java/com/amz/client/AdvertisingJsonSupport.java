package com.amz.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;

/**
 * Ads API v3 响应的 JSON 取值工具（无状态纯函数）。
 * <p>
 * <b>为什么从 {@link AdvertisingApiRealClient} 抽出来</b>：那个类已 777 行，超过
 * CLAUDE.md 的 500 行上限。抽出的这部分与业务流程无关——它只回答「这个 JSON 字段
 * 该以什么类型取出来，缺了算什么」。留在主类里既占行数，又让「解析规则」和
 * 「调用顺序/重试/令牌」混在同一个编译单元里。
 * <p>
 * <b>为什么缺失/类型不符一律抛 {@link AdvertisingApiException}（AD_INVALID_RESPONSE）</b>：
 * 上游契约漂移时，返回 null 会让调用方把「字段没解析到」当成「字段本来就是空」，
 * 最终表现为运营看到一张空报表而不是一条错误。fail-closed 才能在日志里立刻看见。
 * <p>
 * <b>为什么 {@code invalidResponse} 放在这里而不是主类</b>：本类所有方法都可能抛它，
 * 放在同一处避免两个类互相引用形成循环依赖。
 */
final class AdvertisingJsonSupport {

    private static final int ERROR_BODY_LIMIT = 300;

    private AdvertisingJsonSupport() {
    }

    /**
     * 取必填数组字段。缺失或不是数组 → 抛。
     * <p>
     * 注意与 {@link #arrayOrEmpty} 的区别：Ads API 的 mutation 响应里
     * {@code success}/{@code error} 是「可选数组」，缺失表示「没有」，不能当成契约破裂。
     */
    static JsonArray array(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonArray()) {
            throw invalidResponse("response missing array field " + field);
        }
        return element.getAsJsonArray();
    }

    /** 取可选数组字段：缺失或 null → 空数组；存在但不是数组 → 抛（那是契约破裂）。 */
    static JsonArray arrayOrEmpty(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return new JsonArray();
        }
        if (!element.isJsonArray()) {
            throw invalidResponse("response field " + field + " is not an array");
        }
        return element.getAsJsonArray();
    }

    /** 取必填字符串：缺失或空白 → 抛。 */
    static String requiredString(JsonObject object, String field) {
        String value = stringValue(object, field);
        if (!hasText(value)) {
            throw invalidResponse("response missing required field " + field);
        }
        return value;
    }

    /** 取可选字符串：缺失/JSON null/非原始值 → null。 */
    static String stringValue(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    /** 取必填数字：缺失或非数字 → 抛。 */
    static Long requiredLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        Long value = longValue(element);
        if (value == null) {
            throw invalidResponse("response missing required numeric field " + field);
        }
        return value;
    }

    /** 取可选 long：缺失 → null；存在但非数字 → 抛。 */
    static Long longValue(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            throw invalidResponse("response contains invalid numeric value: " + element);
        }
    }

    /** 取可选 int：范围越界 → 抛（静默截断会让数量类指标错得毫无痕迹）。 */
    static Integer intValue(JsonElement element) {
        Long value = longValue(element);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalidResponse("response integer value out of range: " + value);
        }
        return value.intValue();
    }

    /** 取可选小数：用 BigDecimal 避免金额/出价经过 double 丢精度。 */
    static BigDecimal decimalValue(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsBigDecimal();
        } catch (RuntimeException e) {
            throw invalidResponse("response contains invalid decimal value: " + element);
        }
    }

    /**
     * 报表下载可能被上游 gzip 压缩（Content-Encoding 与实际字节不一定一致，
     * 所以按魔数判断而不是看响应头）。
     */
    static byte[] decodeGzipIfNeeded(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 2 || (bytes[0] & 0xFF) != 0x1F || (bytes[1] & 0xFF) != 0x8B) {
            return bytes == null ? new byte[0] : bytes;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzip.transferTo(out);
            return out.toByteArray();
        }
    }

    /** URL 查询参数编码：null 视为空串，避免 "null" 字面量被送进上游。 */
    static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * 报表轮询的等待。
     * <p>
     * 被中断时<b>恢复中断标记</b>再抛：吞掉 InterruptedException 会让上层的
     * 超时/关闭信号失效，线程继续跑完整轮轮询。
     */
    static void sleep(java.time.Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdvertisingApiException("AD_TRANSPORT_ERROR", "Report polling interrupted",
                    null, true, e);
        }
    }

    /** 去掉末尾所有 '/'：endpoint 常带尾斜杠，拼接后会变成双斜杠。 */
    static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 截断用于日志/异常信息的文本，避免把整个响应体灌进日志或错误消息。 */
    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 统一的「上游响应不符合契约」异常：fail-closed，带可检索的错误码。 */
    static AdvertisingApiException invalidResponse(String message) {
        return new AdvertisingApiException("AD_INVALID_RESPONSE", message, null, false);
    }

    /** 令牌缓存键用的指纹：不保存原始 refresh token，只保存它的摘要。 */
    static String sha256Hex(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 错误响应体的可读摘要。
     * <p>
     * 取值顺序保持不变（这是一个已上线的诊断口径，改动会让历史日志与新日志不可比）：
     * {@code errors[0].message} → 顶层 {@code message} → 原文截断。
     */
    static String errorDetail(String body) {
        if (!hasText(body)) {
            return "";
        }
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            JsonElement errors = root.get("errors");
            if (errors != null && errors.isJsonArray() && !errors.getAsJsonArray().isEmpty()) {
                JsonElement first = errors.getAsJsonArray().get(0);
                if (first.isJsonObject()) {
                    String message = stringValue(first.getAsJsonObject(), "message");
                    if (hasText(message)) {
                        return ": " + truncate(message, ERROR_BODY_LIMIT);
                    }
                }
            }
            String message = stringValue(root, "message");
            if (hasText(message)) {
                return ": " + truncate(message, ERROR_BODY_LIMIT);
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体：退回原始截断
        }
        return ": " + truncate(body, ERROR_BODY_LIMIT);
    }
}
