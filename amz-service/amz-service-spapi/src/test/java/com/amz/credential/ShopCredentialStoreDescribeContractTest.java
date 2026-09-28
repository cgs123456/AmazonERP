package com.amz.credential;

import com.amz.mapper.ShopCredentialMapper;
import com.amz.model.ShopCredentialEntity;
import com.amz.util.CryptoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ShopCredentialStore#describe(Long)} 的鲁棒性契约。
 *
 * <p>背景（第 32 轮真机取证发现）：管理端状态查询原走 {@code getForAdmin}，
 * 该方法会解密四个密钥。一旦密文损坏（本轮是合成数据里含 {@code -} 的非法
 * base64，生产等价场景是密钥轮换不匹配或数据被手工改坏），解密抛出
 * {@code IllegalArgumentException: Illegal base64 character 2d}，
 * 状态查询直接 500——管理员既看不到记录，也失去了修复入口。</p>
 *
 * <p>本契约锁定：{@code describe} 永不解密，因此在同样的坏数据上仍能返回
 * 存在性布尔位；而 {@code getForAdmin} 保持 fail-closed（仍然抛），
 * 两者职责不混淆。</p>
 */
@DisplayName("ShopCredentialStore.describe 无明文读取契约")
class ShopCredentialStoreDescribeContractTest {

    /** 含 '-' 的非 base64 字符串，与合成数据集里损坏的凭证列同形。 */
    private static final String CORRUPT_CIPHERTEXT = "SYNTHETIC-NOT-A-REAL-SECRET-000008";

    private ShopCredentialMapper mapper;
    private CryptoUtil cryptoUtil;
    private ShopCredentialStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(ShopCredentialMapper.class);
        cryptoUtil = new CryptoUtil();
        ReflectionTestUtils.setField(cryptoUtil, "cryptoKey", Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        cryptoUtil.init();

        store = new ShopCredentialStore();
        ReflectionTestUtils.setField(store, "shopCredentialMapper", mapper);
        ReflectionTestUtils.setField(store, "cryptoUtil", cryptoUtil);
        store.loadFromDb();
    }

    @Test
    @DisplayName("密文损坏时 describe 仍返回存在性布尔位，不解密也不抛")
    void describeSurvivesCorruptCiphertext() {
        when(mapper.selectById(900L)).thenReturn(entityWithCorruptSecrets(900L));

        ShopCredentialDescriptor descriptor = assertDoesNotThrow(() -> store.describe(900L));

        assertNotNull(descriptor);
        assertEquals(900L, descriptor.shopId());
        assertTrue(descriptor.clientSecretPresent());
        assertTrue(descriptor.refreshTokenPresent());
        assertTrue(descriptor.accessKeyPresent());
        assertTrue(descriptor.secretKeyPresent());
        assertEquals("NA", descriptor.region());
        assertEquals("ATVPDKIKX0DER", descriptor.marketplaceId());
    }

    @Test
    @DisplayName("同一份坏数据上 getForAdmin 仍然抛（fail-closed 语义不降级）")
    void getForAdminStillFailsClosedOnCorruptCiphertext() {
        when(mapper.selectById(900L)).thenReturn(entityWithCorruptSecrets(900L));

        assertThrows(IllegalArgumentException.class, () -> store.getForAdmin(900L));
    }

    @Test
    @DisplayName("记录不存在时 describe 返回 null，不伪造状态")
    void describeReturnsNullWhenAbsent() {
        when(mapper.selectById(901L)).thenReturn(null);

        assertNull(store.describe(901L));
    }

    @Test
    @DisplayName("healthy 记录：describe 的布尔位与真实配置一致")
    void describeReportsAbsentSecretsWhenColumnsAreNull() {
        ShopCredentialEntity entity = new ShopCredentialEntity();
        entity.setShopId(902L);
        entity.setClientId("client-902");
        entity.setRegion("EU");
        entity.setMarketplaceId("A1PA6795UKMFR9");
        when(mapper.selectById(902L)).thenReturn(entity);

        ShopCredentialDescriptor descriptor = store.describe(902L);

        assertNotNull(descriptor);
        assertTrue(descriptor.clientId() != null && !descriptor.clientId().isBlank());
        assertFalse(descriptor.clientSecretPresent());
        assertFalse(descriptor.refreshTokenPresent());
        assertFalse(descriptor.accessKeyPresent());
        assertFalse(descriptor.secretKeyPresent());
    }

    private static ShopCredentialEntity entityWithCorruptSecrets(long shopId) {
        ShopCredentialEntity entity = new ShopCredentialEntity();
        entity.setShopId(shopId);
        entity.setClientId("client-" + shopId);
        entity.setClientSecretEncrypted(CORRUPT_CIPHERTEXT);
        entity.setRefreshTokenEncrypted(CORRUPT_CIPHERTEXT);
        entity.setAccessKeyEncrypted(CORRUPT_CIPHERTEXT);
        entity.setSecretKeyEncrypted(CORRUPT_CIPHERTEXT);
        entity.setRegion("NA");
        entity.setMarketplaceId("ATVPDKIKX0DER");
        entity.setSellerId("seller-" + shopId);
        return entity;
    }
}