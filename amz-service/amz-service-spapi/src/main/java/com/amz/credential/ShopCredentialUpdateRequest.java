package com.amz.credential;

import lombok.Data;

/**
 * SP-API 凭证部分更新请求。
 *
 * <p>字符串字段为 null 或空白时保留旧值。AWS 密钥只能成对提交、
 * 都不提交，或通过 {@link #clearAwsKeys} 显式清空。</p>
 *
 * <p>请求体刻意不包含 shopId；目标店铺只能来自路径变量。</p>
 */
@Data
public class ShopCredentialUpdateRequest {

    private String clientId;

    private String clientSecret;

    private String refreshToken;

    private String accessKey;

    private String secretKey;

    private String region;

    private String marketplaceId;

    private String sellerId;

    /** true 时清空已有 AWS 密钥，且不得同时提交 accessKey/secretKey。 */
    private boolean clearAwsKeys;
}
