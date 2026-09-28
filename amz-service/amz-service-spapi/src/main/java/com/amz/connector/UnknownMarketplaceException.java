package com.amz.connector;

/**
 * marketplace / region 解析失败（P0-36 fail-closed）。
 * <p>
 * 旧实现为 4 份各 10 条的硬编码表 + {@code getOrDefault(marketplaceId, "NA")}：
 * 未登记的 marketplaceId 会被静默当作北美，请求直接打到错误区域端点，日志无任何提示。
 * 现在解析失败必须**显式抛出**，并用 {@link #code()} 区分两类原因，便于运维定位与告警分流。
 * <p>
 * 继承 {@link IllegalArgumentException}：与旧实现抛出的
 * {@code IllegalArgumentException("No SP-API endpoint for region=...")} 保持兼容，
 * 既有 catch 语义不变。
 */
public class UnknownMarketplaceException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** 未登记的 marketplaceId（含 null / 空白 / 大小写或首尾空格不符）。 */
    public static final String CODE_UNKNOWN_MARKETPLACE = "SPAPI_UNKNOWN_MARKETPLACE";

    /** region 不属于官方 NA / EU / FE，或凭证与请求里都没有可用的 marketplace/region。 */
    public static final String CODE_UNSUPPORTED_REGION = "SPAPI_UNSUPPORTED_REGION";

    private final String code;
    private final String marketplaceId;
    private final String region;

    public UnknownMarketplaceException(String code, String message) {
        this(code, message, null, null);
    }

    public UnknownMarketplaceException(String code, String message, String marketplaceId, String region) {
        super(message);
        this.code = code;
        this.marketplaceId = marketplaceId;
        this.region = region;
    }

    /** 稳定错误码，见 {@link #CODE_UNKNOWN_MARKETPLACE} / {@link #CODE_UNSUPPORTED_REGION}。 */
    public String code() {
        return code;
    }

    /** 触发异常的 marketplaceId（可能为 null）。 */
    public String marketplaceId() {
        return marketplaceId;
    }

    /** 触发异常的 region（可能为 null）。 */
    public String region() {
        return region;
    }
}
