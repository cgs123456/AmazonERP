package com.amz.credential;

import java.time.LocalDateTime;

/**
 * 店铺 SP-API 凭证的「无明文」结构视图。
 *
 * <p>用于只需要判断「有没有配置」而不需要使用凭证本身的场景（管理端状态面板、
 * 删除/修复坏记录）。四个敏感字段只暴露<strong>密文是否存在</strong>的布尔位，
 * 绝不携带密文，更不解密，因此：
 * <ul>
 *   <li>读取状态不会把 clientSecret / refreshToken / accessKey / secretKey
 *       的明文 materialize 到内存，贯彻「只写不回显」；</li>
 *   <li>密文损坏或密钥轮换不匹配时，状态查询不会连带 500，
 *       管理员仍能看见记录并删除重建。</li>
 * </ul>
 *
 * <p>真正发起 SP-API 调用仍必须走 fail-closed 的 {@code ShopCredentialStore#get}。</p>
 */
public record ShopCredentialDescriptor(
        Long shopId,
        String clientId,
        boolean clientSecretPresent,
        boolean refreshTokenPresent,
        boolean accessKeyPresent,
        boolean secretKeyPresent,
        String region,
        String marketplaceId,
        String sellerId,
        LocalDateTime updateTime) {
}
