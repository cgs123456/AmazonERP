package com.amz.result;

/**
 * 统一的机器可读错误载荷。
 * <p>
 * 该对象只承载可安全暴露给调用方的字段：本地错误分类、上游 HTTP 状态码、平台错误码、
 * 平台错误消息与平台请求 ID。异常堆栈、凭证、签名 URL 和完整响应体不得进入这里。
 */
public class ApiError {

    /** 本地稳定错误分类，例如 {@code SPAPI_CALL_FAILED}。 */
    private String code;

    /** 上游 HTTP 状态码；没有收到 HTTP 响应时为 {@code null}。 */
    private Integer platformStatus;

    /** 平台返回的 {@code errors[].code}；平台未返回时为 {@code null}。 */
    private String platformCode;

    /** 平台返回的 {@code errors[].message}；平台未返回时为 {@code null}。 */
    private String platformMessage;

    /** 平台请求 ID，用于向 Amazon 支持团队定位单次调用。 */
    private String requestId;

    public ApiError() {
    }

    public ApiError(String code, Integer platformStatus, String platformCode,
                    String platformMessage, String requestId) {
        this.code = code;
        this.platformStatus = platformStatus;
        this.platformCode = platformCode;
        this.platformMessage = platformMessage;
        this.requestId = requestId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getPlatformStatus() {
        return platformStatus;
    }

    public void setPlatformStatus(Integer platformStatus) {
        this.platformStatus = platformStatus;
    }

    public String getPlatformCode() {
        return platformCode;
    }

    public void setPlatformCode(String platformCode) {
        this.platformCode = platformCode;
    }

    public String getPlatformMessage() {
        return platformMessage;
    }

    public void setPlatformMessage(String platformMessage) {
        this.platformMessage = platformMessage;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
