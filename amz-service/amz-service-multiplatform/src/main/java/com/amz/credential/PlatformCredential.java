package com.amz.credential;

import lombok.Data;

/**
 * 平台凭证值对象：按 (shopId, platform) 解析后的可用凭证。
 * <p>
 * 敏感字段（appSecret / accessToken）由 {@link PlatformCredentialService}
 * 从 amz_platform_account 表的加密列解密后得到明文，仅在内存中短暂持有，
 * 不落盘、不写日志（日志一律经 {@code AbstractPlatformClient#mask} 脱敏）。
 */
@Data
public class PlatformCredential {

    /** 平台 appKey（部分平台称 client_id）。 */
    private String appKey;

    /** 平台 appSecret（用于签名 / 换取 token）。 */
    private String appSecret;

    /** 平台 accessToken（Temu / TikTok 透传用）。 */
    private String accessToken;

    /** API 端点；账号未配置时由服务按平台给出默认值。 */
    private String apiBase;

    public PlatformCredential() {
    }

    public PlatformCredential(String appKey, String appSecret, String accessToken, String apiBase) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.accessToken = accessToken;
        this.apiBase = apiBase;
    }

    /**
     * 空凭证：所有字段为 null。供「未配置」场景返回，
     * 调用方据此触发既有的诚实失败逻辑（与原 @Value 空默认值行为一致）。
     */
    public static PlatformCredential empty() {
        return new PlatformCredential(null, null, null, null);
    }
}
