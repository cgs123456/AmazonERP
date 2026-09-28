package com.amz.connector;

import java.util.Locale;

/**
 * 本地失败的类型化异常。
 * <p>
 * 适用于请求尚未发出时就能确定的错误，例如预签名 URL 非法、请求体缺失或本地配置缺失。
 * 本类不表示 Amazon 返回了失败响应，因此不得携带平台 HTTP 状态或平台错误码。
 * 消息只保留经过 {@link ErrorSummary#sanitize(String)} 处理的诊断文本，且不链 cause，
 * 避免 URI 解析器等上游组件把完整预签名 URL 带进日志。
 */
public final class LocalApiException extends RuntimeException {

    public static final String CODE_INVALID_REQUEST = "INVALID_REQUEST";
    public static final String CODE_CONFLICT = "CONFLICT";
    public static final String CODE_PRESIGNED_URL_INVALID = "PRESIGNED_URL_INVALID";
    public static final String CODE_CREDENTIAL_MISSING = "CREDENTIAL_MISSING";
    public static final String CODE_MARKETPLACE_MISSING = "MARKETPLACE_MISSING";
    public static final String CODE_FORBIDDEN = "FORBIDDEN";
    public static final String CODE_CONNECTOR_NOT_FOUND = "CONNECTOR_NOT_FOUND";
    public static final String CODE_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    private final String code;

    private LocalApiException(String code, String diagnostic) {
        super(ErrorSummary.sanitize(diagnostic));
        this.code = normalizeCode(code);
    }

    public static LocalApiException of(String code, String diagnostic) {
        return new LocalApiException(code, diagnostic);
    }

    public String getCode() {
        return code;
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "LOCAL_ERROR";
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
