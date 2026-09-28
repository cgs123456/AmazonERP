package com.amz.credential;

import com.amz.auth.LwaTokenManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证录入/轮换的服务层契约。
 *
 * <p>核心安全语义：敏感字段只写不回显；更新时留空表示保留旧值；
 * AWS 密钥只能成对替换或显式清空；写成功后必须让新旧 LWA token 失效。</p>
 */
@DisplayName("店铺 SP-API 凭证管理服务")
class ShopCredentialAdminServiceTest {

    private ShopCredentialStore store;
    private LwaTokenManager tokenManager;
    private ShopCredentialAdminService service;

    @BeforeEach
    void setUp() {
        store = mock(ShopCredentialStore.class);
        tokenManager = mock(LwaTokenManager.class);
        service = new ShopCredentialAdminService(store, tokenManager);
    }

    @Test
    @DisplayName("状态响应只含配置布尔值和路由元数据，不含任何敏感字段")
    void statusDoesNotExposeSecrets() {
        when(store.describe(7L)).thenReturn(descriptor(7L));

        ShopCredentialStatus status = service.status(7L);

        assertTrue(status.configured());
        assertTrue(status.clientIdConfigured());
        assertTrue(status.clientSecretConfigured());
        assertTrue(status.refreshTokenConfigured());
        assertFalse(status.awsKeysConfigured());
        assertEquals("NA", status.region());
        assertEquals("ATVPDKIKX0DER", status.marketplaceId());
        assertEquals("seller", status.sellerId());
        assertEquals(LocalDateTime.of(2026, 9, 27, 10, 0), status.updatedAt());
    }

    @Test
    @DisplayName("未配置店铺返回 configured=false，不伪造敏感字段状态")
    void missingCredentialReturnsUnconfiguredStatus() {
        when(store.describe(7L)).thenReturn(null);

        ShopCredentialStatus status = service.status(7L);

        assertFalse(status.configured());
        assertFalse(status.clientIdConfigured());
        assertFalse(status.clientSecretConfigured());
        assertFalse(status.refreshTokenConfigured());
        assertFalse(status.awsKeysConfigured());
        assertNull(status.updatedAt());
    }

    @Test
    @DisplayName("incomplete credential status is field-level and does not throw")
    void incompleteCredentialReturnsFieldLevelStatus() {
        ShopCredentialDescriptor incomplete = new ShopCredentialDescriptor(7L, "configured-client",
                false, false, false, false, null, null, null, null);
        when(store.describe(7L)).thenReturn(incomplete);

        ShopCredentialStatus status = service.status(7L);

        assertFalse(status.configured());
        assertTrue(status.clientIdConfigured());
        assertFalse(status.clientSecretConfigured());
        assertFalse(status.refreshTokenConfigured());
        assertFalse(status.awsKeysConfigured());
        verify(store).describe(7L);
    }

    @Test
    @DisplayName("状态查询不解密任何密钥：绝不触碰需要解密的读取路径")
    void statusNeverUsesDecryptingReadPath() {
        when(store.describe(7L)).thenReturn(descriptor(7L));

        service.status(7L);

        verify(store, never()).getForAdmin(anyLong());
        verify(store, never()).get(anyLong());
    }

    @Test
    @DisplayName("部分更新只改提交字段，未提交的 LWA 密钥保留")
    void partialUpdatePreservesUnsubmittedSecrets() {
        ShopCredential existing = credential(7L);
        existing.setClientSecret("old-secret");
        existing.setRefreshToken("old-refresh");
        existing.setVersion(3L);
        when(store.getForAdmin(7L)).thenReturn(existing);
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setSellerId("new-seller");
        request.setClientSecret(" ");
        request.setRefreshToken(null);

        ShopCredentialStatus status = service.upsert(7L, request);

        ArgumentCaptor<ShopCredential> captor = ArgumentCaptor.forClass(ShopCredential.class);
        verify(store).putIfVersion(captor.capture(), anyLong());
        ShopCredential saved = captor.getValue();
        assertEquals(7L, saved.getShopId());
        assertEquals(3L, saved.getVersion());
        assertEquals("old-secret", saved.getClientSecret());
        assertEquals("old-refresh", saved.getRefreshToken());
        assertEquals("new-seller", saved.getSellerId());
        assertNotNull(saved.getUpdateTime());
        verify(tokenManager).invalidate(existing);
        verify(tokenManager).invalidate(saved);
        assertTrue(status.configured());
    }

    @Test
    @DisplayName("提交新 clientSecret/refreshToken 时执行轮换，而不是沿用旧值")
    void explicitRotationReplacesProvidedSecrets() {
        ShopCredential existing = credential(7L);
        existing.setClientSecret("old-secret");
        existing.setRefreshToken("old-refresh");
        existing.setVersion(2L);
        when(store.getForAdmin(7L)).thenReturn(existing);
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setClientSecret("new-secret");
        request.setRefreshToken("new-refresh");

        service.upsert(7L, request);

        ArgumentCaptor<ShopCredential> captor = ArgumentCaptor.forClass(ShopCredential.class);
        verify(store).putIfVersion(captor.capture(), anyLong());
        assertEquals("new-secret", captor.getValue().getClientSecret());
        assertEquals("new-refresh", captor.getValue().getRefreshToken());
    }

    @Test
    @DisplayName("AWS 密钥可显式清空，同时清空旧的一对值")
    void awsKeyPairCanBeCleared() {
        ShopCredential existing = credential(7L);
        existing.setAccessKey("old-access");
        existing.setSecretKey("old-secret-key");
        existing.setVersion(4L);
        when(store.getForAdmin(7L)).thenReturn(existing);
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setClearAwsKeys(true);

        service.upsert(7L, request);

        ArgumentCaptor<ShopCredential> captor = ArgumentCaptor.forClass(ShopCredential.class);
        verify(store).putIfVersion(captor.capture(), anyLong());
        assertNull(captor.getValue().getAccessKey());
        assertNull(captor.getValue().getSecretKey());
    }

    @Test
    @DisplayName("不能只提交一半 AWS 密钥形成错配")
    void partialAwsKeyPairIsRejectedWithoutWrite() {
        when(store.getForAdmin(7L)).thenReturn(credential(7L));
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setAccessKey("new-access");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(7L, request));

        assertTrue(error.getMessage().contains("accessKey/secretKey"));
        assertFalse(error.getMessage().contains("new-access"));
        verify(store, never()).putIfVersion(any(ShopCredential.class), anyLong());
    }

    @Test
    @DisplayName("显式清空 AWS 密钥时不得同时提交新值")
    void clearAndReplaceAwsKeysAreMutuallyExclusive() {
        when(store.getForAdmin(7L)).thenReturn(credential(7L));
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setClearAwsKeys(true);
        request.setAccessKey("new-access");
        request.setSecretKey("new-secret");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(7L, request));

        assertTrue(error.getMessage().contains("clearAwsKeys"));
        verify(store, never()).putIfVersion(any(ShopCredential.class), anyLong());
    }

    @Test
    @DisplayName("创建时缺少 LWA 三要素或路由必须拒绝，且不得写库")
    void createRejectsMissingRequiredFields() {
        when(store.getForAdmin(7L)).thenReturn(null);
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setClientId("client-only");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.upsert(7L, request));

        assertTrue(error.getMessage().contains("clientSecret"));
        assertTrue(error.getMessage().contains("refreshToken"));
        assertTrue(error.getMessage().contains("marketplaceId/region"));
        verify(store, never()).putIfVersion(any(ShopCredential.class), anyLong());
    }

    @Test
    @DisplayName("create uses null expected version")
    void createUsesNullExpectedVersion() {
        when(store.getForAdmin(7L)).thenReturn(null);

        service.upsert(7L, completeRequest());

        verify(store).putIfVersion(any(ShopCredential.class), isNull());
        verify(store, never()).put(any(ShopCredential.class));
    }

    @Test
    @DisplayName("CAS conflict evicts cache and replays the request against the latest version")
    void upsertRetriesCasConflictAgainstLatestVersion() {
        ShopCredential first = credential(7L);
        first.setVersion(3L);
        ShopCredential latest = credential(7L);
        latest.setVersion(4L);
        latest.setSellerId("concurrent-seller");
        when(store.getForAdmin(7L)).thenReturn(first, latest);
        doThrow(new ShopCredentialConcurrentUpdateException(7L))
                .doNothing()
                .when(store).putIfVersion(any(ShopCredential.class), anyLong());
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setSellerId("requested-seller");

        ShopCredentialStatus status = service.upsert(7L, request);

        ArgumentCaptor<ShopCredential> credentials = ArgumentCaptor.forClass(ShopCredential.class);
        ArgumentCaptor<Long> versions = ArgumentCaptor.forClass(Long.class);
        verify(store, times(2)).putIfVersion(credentials.capture(), versions.capture());
        assertEquals(List.of(3L, 4L), versions.getAllValues());
        assertEquals(List.of(3L, 4L), credentials.getAllValues().stream()
                .map(ShopCredential::getVersion).toList());
        assertEquals("requested-seller", credentials.getAllValues().get(1).getSellerId());
        verify(store).evictCache(7L);
        assertTrue(status.configured());
    }

    @Test
    @DisplayName("CAS retries fail closed after the retry budget is exhausted")
    void upsertFailsClosedWhenCasRetriesAreExhausted() {
        ShopCredential first = credential(7L);
        first.setVersion(3L);
        ShopCredential second = credential(7L);
        second.setVersion(4L);
        ShopCredential third = credential(7L);
        third.setVersion(5L);
        when(store.getForAdmin(7L)).thenReturn(first, second, third);
        doThrow(new ShopCredentialConcurrentUpdateException(7L))
                .when(store).putIfVersion(any(ShopCredential.class), anyLong());

        assertThrows(ShopCredentialConcurrentUpdateException.class,
                () -> service.upsert(7L, completeRequest()));

        verify(store, times(3)).getForAdmin(7L);
        verify(store, times(3)).putIfVersion(any(ShopCredential.class), anyLong());
        verify(store, times(3)).evictCache(7L);
    }

    @Test
    @DisplayName("删除凭证时清除旧 token，不返回任何密文")
    void deleteInvalidatesExistingToken() {
        when(store.describe(7L)).thenReturn(descriptor(7L));

        ShopCredentialStatus status = service.delete(7L);

        assertFalse(status.configured());
        verify(tokenManager).invalidate("amzn1.application-oa2-client.test");
        verify(store).remove(7L);
    }

    @Test
    @DisplayName("incomplete credential can still be deleted")
    void deleteToleratesIncompleteCredential() {
        ShopCredentialDescriptor incomplete = new ShopCredentialDescriptor(7L, "client-only",
                false, false, false, false, null, null, null, null);
        when(store.describe(7L)).thenReturn(incomplete);

        ShopCredentialStatus status = service.delete(7L);

        assertFalse(status.configured());
        verify(store).remove(7L);
        verify(tokenManager).invalidate("client-only");
    }

    @Test
    @DisplayName("密文损坏时删除仍然成功：不因解密失败而失去修复手段")
    void deleteRemovesEvenWhenSecretsArePresentOnlyAsCiphertext() {
        ShopCredentialDescriptor broken = new ShopCredentialDescriptor(7L, "amzn1.application-oa2-client.test",
                true, true, true, true, "NA", "ATVPDKIKX0DER", "seller", null);
        when(store.describe(7L)).thenReturn(broken);

        ShopCredentialStatus status = service.delete(7L);

        assertFalse(status.configured());
        verify(store).remove(7L);
        verify(tokenManager).invalidate("amzn1.application-oa2-client.test");
        verify(store, never()).getForAdmin(anyLong());
    }

    private static ShopCredentialUpdateRequest completeRequest() {
        ShopCredentialUpdateRequest request = new ShopCredentialUpdateRequest();
        request.setClientId("amzn1.application-oa2-client.test");
        request.setClientSecret("client-secret");
        request.setRefreshToken("refresh-token");
        request.setRegion("NA");
        request.setMarketplaceId("ATVPDKIKX0DER");
        request.setSellerId("seller");
        return request;
    }

    private static ShopCredentialDescriptor descriptor(long shopId) {
        return new ShopCredentialDescriptor(shopId, "amzn1.application-oa2-client.test",
                true, true, false, false, "NA", "ATVPDKIKX0DER", "seller",
                LocalDateTime.of(2026, 9, 27, 10, 0));
    }

    private static ShopCredential credential(long shopId) {
        ShopCredential credential = new ShopCredential();
        credential.setShopId(shopId);
        credential.setClientId("amzn1.application-oa2-client.test");
        credential.setClientSecret("client-secret");
        credential.setRefreshToken("refresh-token");
        credential.setRegion("NA");
        credential.setMarketplaceId("ATVPDKIKX0DER");
        credential.setSellerId("seller");
        credential.setUpdateTime(LocalDateTime.of(2026, 9, 27, 10, 0));
        return credential;
    }
}