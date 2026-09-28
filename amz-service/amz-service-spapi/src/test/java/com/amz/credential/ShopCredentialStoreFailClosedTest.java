package com.amz.credential;

import com.amz.mapper.ShopCredentialMapper;
import com.amz.model.ShopCredentialEntity;
import com.amz.util.CryptoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 店铺凭证存储的生产 fail-closed 契约。
 * <p>
 * 生产 profile 中，DB 故障不得被解释为“凭证不存在”、不得在落库失败后假装保存成功，
 * 也不得在删除失败后让内存与数据库长期分叉。离线/显式非生产模式保留可控降级。
 */
@DisplayName("ShopCredentialStore 生产 fail-closed 契约")
class ShopCredentialStoreFailClosedTest {

    private ShopCredentialMapper mapper;
    private CryptoUtil cryptoUtil;

    @BeforeEach
    void setUp() {
        mapper = mock(ShopCredentialMapper.class);
        cryptoUtil = mock(CryptoUtil.class);
        when(cryptoUtil.encrypt(anyString())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        when(cryptoUtil.decrypt(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value == null ? null : value.replaceFirst("^enc:", "");
        });
    }

    @Test
    @DisplayName("生产模式：启动阶段不得预加载整张凭证表")
    void productionStartupDoesNotPreloadCredentials() {
        ShopCredentialStore store = store(true);

        assertDoesNotThrow(store::loadFromDb);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("生产模式：活跃店铺 ID 查询失败必须抛出，不能伪装成没有店铺")
    void productionActiveShopIdsDoesNotTurnDatabaseFailureIntoEmpty() {
        ShopCredentialStore store = store(true);
        when(mapper.selectShopIdsAfter(anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, store::getActiveShopIds);
    }

    @Test
    @DisplayName("生产模式：缓存未命中时 DB 故障必须抛出，不能伪装成凭证不存在")
    void productionGetDoesNotTurnDatabaseFailureIntoMissingCredential() {
        ShopCredentialStore store = store(true);
        when(mapper.selectById(7L)).thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () -> store.get(7L));
    }

    @Test
    @DisplayName("生产模式：DB 写入失败必须抛出，且不得先更新内存")
    void productionPutPersistsBeforeUpdatingCache() {
        ShopCredentialStore store = store(true);
        when(mapper.selectById(7L)).thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () -> store.put(credential(7L)));
        assertTrue(cache(store).size() == 0, "DB 失败时不得留下看似已保存的内存凭证");
    }

    @Test
    @DisplayName("生产模式：DB 删除失败必须抛出，且保留内存凭证")
    void productionRemoveKeepsCacheWhenDatabaseDeleteFails() {
        ShopCredentialStore store = store(true);
        cache(store).put(7L, encryptedCredential(7L));
        when(mapper.deleteById(7L)).thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () -> store.remove(7L));
        assertNotNull(cache(store).get(7L), "删除未落库时不得单方面从内存移除");
    }

    @Test
    @DisplayName("CAS success increments the version before exposing it")
    void putIfVersionCachesTheIncrementedVersionAfterDatabaseSuccess() {
        ShopCredentialStore store = store(true);
        ShopCredential credential = credential(7L);
        credential.setVersion(2L);
        when(mapper.updateIfVersion(any(), eq(2L))).thenReturn(1);

        store.putIfVersion(credential, 2L);

        ShopCredential cached = store.get(7L);
        assertEquals(3L, cached.getVersion());
        verify(mapper).updateIfVersion(any(), eq(2L));
    }

    @Test
    @DisplayName("CAS zero-row update is a conflict and does not poison the cache")
    void putIfVersionRejectsStaleVersionWithoutCacheWrite() {
        ShopCredentialStore store = store(true);
        ShopCredential credential = credential(7L);
        credential.setVersion(2L);
        when(mapper.updateIfVersion(any(), eq(2L))).thenReturn(0);

        assertThrows(ShopCredentialConcurrentUpdateException.class,
                () -> store.putIfVersion(credential, 2L));

        assertEquals(0, cache(store).size());
    }

    @Test
    @DisplayName("concurrent create conflict is not degraded into a cache-only write")
    void putIfVersionCreateConflictFailsClosed() {
        ShopCredentialStore store = store(true);
        when(mapper.insert(any(ShopCredentialEntity.class))).thenThrow(new DuplicateKeyException("duplicate shop_id"));

        assertThrows(ShopCredentialConcurrentUpdateException.class,
                () -> store.putIfVersion(credential(7L), null));

        assertEquals(0, cache(store).size());
    }

    @Test
    @DisplayName("unconditional put uses the atomic version-increment SQL path")
    void putUsesUnconditionalAtomicUpdateInsteadOfUpdateById() {
        ShopCredentialStore store = store(true);
        ShopCredentialEntity existing = new ShopCredentialEntity();
        existing.setShopId(7L);
        when(mapper.selectById(7L)).thenReturn(existing);
        when(mapper.updateUnconditionally(any(ShopCredentialEntity.class))).thenReturn(1);

        store.put(credential(7L));

        ArgumentCaptor<ShopCredentialEntity> entity = ArgumentCaptor.forClass(ShopCredentialEntity.class);
        verify(mapper).updateUnconditionally(entity.capture());
        verify(mapper, never()).updateById(any(ShopCredentialEntity.class));
        assertEquals(7L, entity.getValue().getShopId());
    }

    @Test
    @DisplayName("写入前校验结构：残缺凭证不得落库或进入缓存")
    void putRejectsInvalidCredentialBeforePersistence() {
        ShopCredentialStore store = store(true);
        ShopCredential credential = credential(7L);
        credential.setRefreshToken(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> store.put(credential));

        assertTrue(ex.getMessage().contains("refreshToken"));
        assertTrue(cache(store).size() == 0);
        verifyNoInteractions(mapper);
    }
    @Test
    @DisplayName("非生产模式：DB 故障保留兼容降级，写失败仍可使用内存缓存")
    void nonProductionKeepsControlledDegradation() {
        ShopCredentialStore store = store(false);
        when(mapper.selectById(7L)).thenThrow(new IllegalStateException("database down"));

        assertDoesNotThrow(() -> store.put(credential(7L)));
        assertNotNull(cache(store).get(7L));
        assertDoesNotThrow(() -> store.remove(7L));
        assertNull(cache(store).get(7L));
    }

    private ShopCredentialStore store(boolean failOnDbError) {
        ShopCredentialStore store = new ShopCredentialStore();
        ReflectionTestUtils.setField(store, "shopCredentialMapper", mapper);
        ReflectionTestUtils.setField(store, "cryptoUtil", cryptoUtil);
        ReflectionTestUtils.setField(store, "failOnDbError", failOnDbError);
        return store;
    }

    private static ShopCredentialCache cache(ShopCredentialStore store) {
        return (ShopCredentialCache) ReflectionTestUtils.getField(store, "store");
    }

    private static ShopCredential credential(long shopId) {
        ShopCredential credential = new ShopCredential();
        credential.setShopId(shopId);
        credential.setClientId("client-" + shopId);
        credential.setClientSecret("client-secret-" + shopId);
        credential.setRefreshToken("refresh-token-" + shopId);
        credential.setRegion("NA");
        credential.setMarketplaceId("ATVPDKIKX0DER");
        credential.setSellerId("seller-" + shopId);
        return credential;
    }

    private static ShopCredential encryptedCredential(long shopId) {
        ShopCredential credential = credential(shopId);
        credential.setClientSecret("enc:" + credential.getClientSecret());
        credential.setRefreshToken("enc:" + credential.getRefreshToken());
        return credential;
    }
}
