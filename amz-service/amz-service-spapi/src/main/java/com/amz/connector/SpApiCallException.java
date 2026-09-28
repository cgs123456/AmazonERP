package com.amz.connector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpResponse;

/**
 * SP-API 非成功响应的类型化异常。
 * <p>
 * 边界层不再从异常文本里猜 HTTP 状态码或平台错误码；网关和三个既有客户端在收到非成功响应时
 * 统一抛出本异常。异常消息仍保留诊断文本（供日志与旧调用方读取），但结构化字段才是下游契约。
 * <p>
 * 本类不携带完整响应体对象，也不携带请求头；只保留状态码、首个平台错误和请求 ID，
 * 避免凭证或大体积响应体经异常链扩散。
 */
public final class SpApiCallException extends RuntimeException {

    private final String operationId;
    private final String path;
    private final int platformStatus;
    private final String platformCode;
    private final String platformMessage;
    private final String requestId;
    private final String diagnostic;

    private SpApiCallException(String operationId, String path, int platformStatus,
                               String platformCode, String platformMessage,
                               String requestId, String diagnostic) {
        super(buildMessage(operationId, path, platformStatus, diagnostic));
        this.operationId = operationId;
        this.path = path;
        this.platformStatus = platformStatus;
        this.platformCode = platformCode;
        this.platformMessage = platformMessage;
        this.requestId = requestId;
        this.diagnostic = diagnostic;
    }

    /**
     * 从 SP-API 响应构造异常；响应为 {@code null} 时状态码记为 -1。
     */
    public static SpApiCallException fromResponse(String operationId, String path,
                                                   HttpResponse<?> response) {
        if (response == null) {
            return new SpApiCallException(operationId, path, -1, null, null, null,
                    "no HTTP response");
        }
        String body = response.body() == null ? "" : response.body().toString();
        ParsedError parsed = parseError(body);
        String diagnostic = ErrorSummary.sanitize(body);
        return new SpApiCallException(operationId, path, response.statusCode(),
                parsed.code(), parsed.message(), requestId(response), diagnostic);
    }

    /**
     * 传输层失败（例如线程中断后没有响应）也必须带 operation/path 抛出，禁止伪装成空结果。
     */
    public static SpApiCallException transportFailure(String operationId, String path, String reason) {
        String diagnostic = ErrorSummary.sanitize(reason == null ? "" : reason);
        return new SpApiCallException(operationId, path, -1, null, null, null, diagnostic);
    }

    /**
     * 本地失败（凭证缺失、令牌刷新失败、请求构造失败、JSON 解析失败等）。
     * <p>
     * 状态码固定为 {@code 0}，与传输失败的 {@code -1} 明确区分：本地失败不可通过
     * SP-API Outbox 自动重试，必须修复配置或代码后再人工重放。
     */
    public static SpApiCallException localFailure(String operationId, String path,
                                                  String localCode, String reason) {
        String diagnostic = ErrorSummary.sanitize(reason == null ? "" : reason);
        return new SpApiCallException(operationId, path, 0, localCode, diagnostic, null, diagnostic);
    }

    /**
     * 仅根据 HTTP 状态码构造异常，适用于 S3 预签名文档上传/下载。
     * <p>
     * 这类响应不是标准 SP-API JSON 错误，且请求 URL 可能含临时凭证；因此只保留状态码与对象路径，
     * 不解析/保存响应体，也不回显完整 URL。
     */
    public static SpApiCallException statusFailure(String operationId, String path, int status) {
        return new SpApiCallException(operationId, path, status, null, null, null,
                "HTTP status " + status);
    }

    public String getOperationId() {
        return operationId;
    }

    public String getPath() {
        return path;
    }

    public int getPlatformStatus() {
        return platformStatus;
    }

    public String getPlatformCode() {
        return platformCode;
    }

    public String getPlatformMessage() {
        return platformMessage;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDiagnostic() {
        return diagnostic;
    }

    private static String buildMessage(String operationId, String path, int status, String diagnostic) {
        return "SP-API call failed operation=" + safe(operationId)
                + " path=" + safe(path)
                + " status=" + status
                + " body=" + safe(diagnostic);
    }

    private static String requestId(HttpResponse<?> response) {
        for (String name : new String[]{"x-amzn-RequestId", "x-amzn-requestid",
                "x-amzn-request-id", "x-amz-request-id"}) {
            String value = response.headers().firstValue(name).orElse(null);
            if (value != null && !value.isBlank()) {
                String sanitized = ErrorSummary.redact(value).trim();
                return sanitized.length() <= 256 ? sanitized : sanitized.substring(0, 256);
            }
        }
        return null;
    }

    private static ParsedError parseError(String body) {
        if (body == null || body.isBlank()) {
            return new ParsedError(null, null);
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return new ParsedError(null, null);
            }
            JsonArray errors = root.getAsJsonObject().getAsJsonArray("errors");
            if (errors == null || errors.isEmpty() || !errors.get(0).isJsonObject()) {
                return new ParsedError(null, null);
            }
            JsonObject first = errors.get(0).getAsJsonObject();
            return new ParsedError(field(first, "code"), field(first, "message"));
        } catch (RuntimeException ignored) {
            return new ParsedError(null, null);
        }
    }

    private static String field(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        String raw = value.getAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ErrorSummary.sanitize(raw);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ParsedError(String code, String message) {
    }
}
