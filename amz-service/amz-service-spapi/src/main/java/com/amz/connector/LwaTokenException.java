package com.amz.connector;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * LWA token 交换失败的稳定分类。
 * <p>
 * LWA 是 SP-API 的认证前置步骤，失败语义不能和业务 SP-API 调用失败混为一谈：
 * 凭证被拒绝、上游 5xx/限流、成功响应契约破损和网络传输失败需要不同的告警与处置。
 * 本异常只保留状态码、OAuth 平台码/描述及已脱敏诊断，不保留原始响应体或异常 cause，
 * 防止 refresh_token / client_secret 或完整 URL 经异常链扩散。
 */
public final class LwaTokenException extends RuntimeException {

    public static final String CODE_AUTH_FAILED = "LWA_AUTH_FAILED";
    public static final String CODE_RATE_LIMITED = "LWA_RATE_LIMITED";
    public static final String CODE_UPSTREAM_ERROR = "LWA_UPSTREAM_ERROR";
    public static final String CODE_INVALID_RESPONSE = "LWA_INVALID_RESPONSE";
    public static final String CODE_TRANSPORT_ERROR = "LWA_TRANSPORT_ERROR";

    private final String code;
    private final Integer platformStatus;
    private final String platformCode;
    private final String platformMessage;
    private final String diagnostic;

    private LwaTokenException(String code, Integer platformStatus, String platformCode,
                              String platformMessage, String diagnostic) {
        super(buildMessage(code, platformStatus, platformCode, diagnostic));
        this.code = code;
        this.platformStatus = platformStatus;
        this.platformCode = platformCode;
        this.platformMessage = platformMessage;
        this.diagnostic = diagnostic;
    }

    /** 从 LWA 非 200 响应构造异常；原始响应体不会进入异常字段。 */
    public static LwaTokenException fromResponse(int status, String body, String clientId) {
        ParsedOAuthError parsed = parseOAuthError(body);
        String code = classifyStatus(status);
        String diagnostic = "LWA token exchange failed status=" + status
                + (parsed.code == null ? "" : " error=" + parsed.code)
                + (parsed.message == null ? "" : " description=" + parsed.message)
                + " clientId=" + safe(clientId);
        return new LwaTokenException(code, status, parsed.code, parsed.message,
                ErrorSummary.sanitize(diagnostic));
    }

    /** 2xx 响应不符合官方 token 契约时使用。 */
    public static LwaTokenException invalidResponse(String diagnostic) {
        return new LwaTokenException(CODE_INVALID_RESPONSE, 200, null, null,
                ErrorSummary.sanitize(diagnostic));
    }

    /** LWA 请求未能取得 HTTP 响应时使用。 */
    public static LwaTokenException transportFailure(String clientId, String reason) {
        String diagnostic = "LWA token transport failure clientId=" + safe(clientId)
                + " reason=" + safe(reason);
        return new LwaTokenException(CODE_TRANSPORT_ERROR, null, null, null,
                ErrorSummary.sanitize(diagnostic));
    }

    public String getCode() {
        return code;
    }

    public Integer getPlatformStatus() {
        return platformStatus;
    }

    public String getPlatformCode() {
        return platformCode;
    }

    public String getPlatformMessage() {
        return platformMessage;
    }

    public String getDiagnostic() {
        return diagnostic;
    }

    private static String classifyStatus(int status) {
        if (status == 429) {
            return CODE_RATE_LIMITED;
        }
        if (status >= 500) {
            return CODE_UPSTREAM_ERROR;
        }
        return CODE_AUTH_FAILED;
    }

    private static ParsedOAuthError parseOAuthError(String body) {
        if (body == null || body.isBlank()) {
            return new ParsedOAuthError(null, null);
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return new ParsedOAuthError(null, null);
            }
            JsonObject object = root.getAsJsonObject();
            return new ParsedOAuthError(field(object, "error"), field(object, "error_description"));
        } catch (RuntimeException ignored) {
            return new ParsedOAuthError(null, null);
        }
    }

    private static String field(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = value.getAsString();
        return text == null || text.isBlank() ? null : ErrorSummary.sanitize(text);
    }

    private static String buildMessage(String code, Integer status, String platformCode,
                                       String diagnostic) {
        return "LWA token exchange failed code=" + code
                + " status=" + (status == null ? "none" : status)
                + " platformCode=" + safe(platformCode)
                + " diagnostic=" + safe(diagnostic);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ParsedOAuthError(String code, String message) {
    }
}
