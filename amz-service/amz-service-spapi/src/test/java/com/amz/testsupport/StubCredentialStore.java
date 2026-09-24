package com.amz.testsupport;

import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内存凭证桩：覆写 {@link ShopCredentialStore#get(Long)}，不触 DB、不做加解密。
 * <p>
 * 用于验证出站请求构造；**不**覆盖凭证加密/持久化路径（那是 {@code ShopCredentialStore} 自身的事）。
 */
public final class StubCredentialStore extends ShopCredentialStore {

    private final Map<Long, ShopCredential> byShop = new LinkedHashMap<>();

    public StubCredentialStore with(ShopCredential credential) {
        byShop.put(credential.getShopId(), credential);
        return this;
    }

    @Override
    public ShopCredential get(Long shopId) {
        return shopId == null ? null : byShop.get(shopId);
    }
}
