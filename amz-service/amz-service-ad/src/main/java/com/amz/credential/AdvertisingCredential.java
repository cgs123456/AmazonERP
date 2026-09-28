package com.amz.credential;

import lombok.Getter;
import lombok.Setter;

/**
 * 单个店铺的 Amazon Advertising API 凭证。
 * <p>
 * Advertising API 与 SP-API 使用不同的权限授权体系，不能复用 SP-API 的
 * refresh token。profileId 也必须与店铺/授权账户一一对应。
 */
@Getter
@Setter
public class AdvertisingCredential {

    private Long shopId;
    private String clientId;
    private String clientSecret;
    private String refreshToken;
    private String profileId;
    private String endpoint;
    private String tokenEndpoint;

    public boolean isComplete() {
        return shopId != null
                && hasText(clientId)
                && hasText(clientSecret)
                && hasText(refreshToken)
                && hasText(profileId)
                && hasText(endpoint)
                && hasText(tokenEndpoint);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
