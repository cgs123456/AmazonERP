package com.amz.credential;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 店铺凭证内存存储。
 * 使用 ConcurrentHashMap 保证多线程下的读写安全。
 * 后续可扩展为从数据库（amz_shop 表）加载 + 缓存。
 */
@Component
public class ShopCredentialStore {

    private final ConcurrentHashMap<Long, ShopCredential> store = new ConcurrentHashMap<>();

    /**
     * 写入或更新店铺凭证。
     */
    public void put(ShopCredential credential) {
        if (credential == null || credential.getShopId() == null) {
            return;
        }
        store.put(credential.getShopId(), credential);
    }

    /**
     * 根据店铺 ID 获取凭证。
     */
    public ShopCredential get(Long shopId) {
        if (shopId == null) {
            return null;
        }
        return store.get(shopId);
    }

    /**
     * 返回当前已配置的全部店铺 ID（即活跃店铺集合）。
     */
    public Set<Long> getActiveShopIds() {
        return store.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * 移除店铺凭证。
     */
    public void remove(Long shopId) {
        if (shopId != null) {
            store.remove(shopId);
        }
    }
}
