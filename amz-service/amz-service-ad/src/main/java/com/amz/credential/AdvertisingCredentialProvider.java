package com.amz.credential;

import java.util.Optional;
import java.util.Set;

/**
 * 按店铺解析 Advertising API 凭证。
 * <p>
 * 生产实现不得提供跨店铺默认凭证；找不到指定店铺时必须返回空值，
 * 由客户端转换为 AD_CREDENTIALS_NOT_CONFIGURED。
 */
@FunctionalInterface
public interface AdvertisingCredentialProvider {

    Optional<AdvertisingCredential> get(Long shopId);


    /**
     * 枚举当前配置中明确绑定店铺的 ID。
     * <p>默认实现为空，保持既有内存/测试实现兼容；配置实现应覆盖此方法，
     * 以便数据库尚无广告活动时调度器仍能发现待同步店铺。
     */
    default Set<Long> configuredShopIds() {
        return Set.of();
    }
}
