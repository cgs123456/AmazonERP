package com.amz.exception;

/**
 * 外部连接器异常：把“未启用 / 未配置 / 已配置但调用失败”表达为互斥状态。
 * <p>
 * 后台任务可以在 {@link Reason#DISABLED} 时明确跳过；只要连接器已启用，
 * 缺少凭证或调用失败都必须向上抛出，禁止返回 {@code null}、空集合或伪造数据。
 */
public class ConnectorException extends RuntimeException {

    public enum Reason {
        /** 功能总开关关闭，调用方应跳过而不是重试。 */
        DISABLED,
        /** 已启用但部署配置不完整，需要运维修复。 */
        NOT_CONFIGURED,
        /** 已配置且已发起调用，但对端、网络或响应失败。 */
        CALL_FAILED
    }

    private final String connector;
    private final Reason reason;

    public ConnectorException(String connector, Reason reason, String message) {
        super(message);
        this.connector = connector;
        this.reason = reason;
    }

    public ConnectorException(String connector, Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.connector = connector;
        this.reason = reason;
    }

    public static ConnectorException disabled(String connector, String message) {
        return new ConnectorException(connector, Reason.DISABLED, message);
    }

    public static ConnectorException notConfigured(String connector, String message) {
        return new ConnectorException(connector, Reason.NOT_CONFIGURED, message);
    }

    public static ConnectorException callFailed(String connector, String message) {
        return new ConnectorException(connector, Reason.CALL_FAILED, message);
    }

    public static ConnectorException callFailed(String connector, String message, Throwable cause) {
        return new ConnectorException(connector, Reason.CALL_FAILED, message, cause);
    }

    public String getConnector() {
        return connector;
    }

    public Reason getReason() {
        return reason;
    }
}
