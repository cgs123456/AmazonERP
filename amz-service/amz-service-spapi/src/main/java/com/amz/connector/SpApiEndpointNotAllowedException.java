package com.amz.connector;

/**
 * 端点主机校验失败（P0-51，fail-closed）。
 * <p>
 * 触发场景：
 * <ol>
 *   <li>{@code spapi.base-url-override} / {@code spapi.lwa-endpoint-override} 指向的主机
 *       既非官方主机也不在白名单内；</li>
 *   <li>调用方传入的 {@code endpoint} 主机与 {@code host} 签名参数不一致
 *       （签名作用域与实际请求主机不同 → 必须拒绝而不是猜）。</li>
 * </ol>
 * <p>
 * 语义上属配置错误：继承 {@link IllegalArgumentException}，与 {@link UnknownMarketplaceException}
 * 的「解析失败即抛」口径一致。
 */
public class SpApiEndpointNotAllowedException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** 稳定错误码，供运维告警与 runbook 判定使用。 */
    public static final String CODE_ENDPOINT_NOT_ALLOWED = "SPAPI_ENDPOINT_NOT_ALLOWED";

    private final String host;

    public SpApiEndpointNotAllowedException(String message, String host) {
        super(message);
        this.host = host;
    }

    /** 稳定错误码，见 {@link #CODE_ENDPOINT_NOT_ALLOWED}。 */
    public String code() {
        return CODE_ENDPOINT_NOT_ALLOWED;
    }

    /** 触发校验失败的主机名（可能为 null）。 */
    public String host() {
        return host;
    }
}
