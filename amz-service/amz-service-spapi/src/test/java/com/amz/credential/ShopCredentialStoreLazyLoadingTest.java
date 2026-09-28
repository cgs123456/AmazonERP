package com.amz.credential;

import com.amz.mapper.ShopCredentialMapper;
import com.amz.util.CryptoUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P0-05：凭证不得在启动阶段全量加载；活跃店铺只查主键，凭证按需读取。
 */
@DisplayName("ShopCredentialStore 按需加载与有界缓存")
class ShopCredentialStoreLazyLoadingTest {

    @Test
    @DisplayName("10,000 店铺启动不读取实体，只按主键分页读取活跃店铺")
    void tenThousandShopsDoNotLoadCredentialEntitiesAtStartup() {
        ShopCredentialMapper mapper = mock(ShopCredentialMapper.class);
        when(mapper.selectShopIdsAfter(anyLong(), anyInt())).thenAnswer(invocation -> {
            long cursor = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            long start = cursor == Long.MIN_VALUE ? 1L : cursor + 1L;
            long end = Math.min(10_000L, start + limit - 1L);
            List<Long> page = new ArrayList<>();
            for (long id = start; id <= end; id++) {
                page.add(id);
            }
            return page;
        });

        ShopCredentialStore store = storeWith(mapper);
        store.loadFromDb();

        verifyNoInteractions(mapper);

        Set<Long> activeShopIds = store.getActiveShopIds();

        assertEquals(10_000, activeShopIds.size());
        assertTrue(activeShopIds.contains(1L));
        assertTrue(activeShopIds.contains(10_000L));
        assertTrue(cache(store).size() == 0, "启动/列店不应把任何凭证实体放入缓存");
        verify(mapper, never()).selectList(any());
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("缓存有容量上限，且 TTL 到期后失效")
    void cacheIsBoundedAndExpires() {
        AtomicLong now = new AtomicLong(1_000L);
        ShopCredentialCache cache = new ShopCredentialCache(2, 100L, now::get);

        cache.put(1L, credential(1L));
        cache.put(2L, credential(2L));
        cache.put(3L, credential(3L));

        assertEquals(2, cache.size());
        assertNull(cache.get(1L), "超过容量后最久未访问的条目应被淘汰");

        now.addAndGet(100L);
        assertNull(cache.get(2L));
        assertEquals(0, cache.size());
    }

    private static ShopCredentialStore storeWith(ShopCredentialMapper mapper) {
        ShopCredentialStore store = new ShopCredentialStore();
        ReflectionTestUtils.setField(store, "shopCredentialMapper", mapper);
        ReflectionTestUtils.setField(store, "cryptoUtil", mock(CryptoUtil.class));
        ReflectionTestUtils.setField(store, "failOnDbError", true);
        ReflectionTestUtils.setField(store, "cacheMaxEntries", 1_000);
        ReflectionTestUtils.setField(store, "cacheTtlSeconds", 600L);
        ReflectionTestUtils.setField(store, "activeShopIdsTtlSeconds", 60L);
        return store;
    }

    private static ShopCredentialCache cache(ShopCredentialStore store) {
        return (ShopCredentialCache) ReflectionTestUtils.getField(store, "store");
    }

    private static ShopCredential credential(long shopId) {
        ShopCredential credential = new ShopCredential();
        credential.setShopId(shopId);
        return credential;
    }
}
