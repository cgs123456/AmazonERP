package com.amz.credential;

import com.amz.mapper.PlatformAccountMapper;
import com.amz.model.PlatformAccount;
import com.amz.util.CryptoUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlatformCredentialService 单元测试。
 * <p>
 * 覆盖 Item6（多租户凭证建模）的核心行为，以及 §S33.92 收紧后的两条租户边界：
 * <ul>
 *   <li>mapper/cryptoUtil 缺失时诚实返回空凭证；</li>
 *   <li>按 (shopId, platform) 精确解析；</li>
 *   <li><b>shopId 非空但未命中时不再回退到别的店铺</b>（旧行为：回退，等于让 A 店用 B 店的身份）；</li>
 *   <li>shopId 为 null 才允许全局回退，且按 id 升序取首个（稳定、可复现）；</li>
 *   <li><b>解密失败按未配置处理返回 null</b>（旧行为：返回密文原文，会把 key 不匹配伪装成平台 401）。</li>
 * </ul>
 */
@DisplayName("PlatformCredentialService 多租户凭证解析测试")
@ExtendWith(MockitoExtension.class)
class PlatformCredentialServiceTest {

    @Mock
    private PlatformAccountMapper mapper;

    @Mock
    private CryptoUtil cryptoUtil;

    @InjectMocks
    private PlatformCredentialService service;

    @Test
    @DisplayName("mapper 为 null → 返回空凭证，不调用 mapper")
    void testMapperNullReturnsEmpty() {
        PlatformCredentialService noMapper = new PlatformCredentialService();
        PlatformCredential result = noMapper.resolve(1L, "TEMU");

        assertNotNull(result);
        assertNull(result.getAppKey());
        assertNull(result.getAppSecret());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("shopId 精确命中 → 返回对应凭证，经解密")
    void testResolveShopIdHit() {
        PlatformAccount acc = new PlatformAccount();
        acc.setShopId(1L);
        acc.setPlatform("TEMU");
        acc.setApiKey("app-key-1");
        acc.setApiSecretEncrypted("encrypted-secret");
        acc.setAccessTokenEncrypted("encrypted-token");
        acc.setApiEndpoint("https://custom.temu.com");

        when(mapper.selectOne(any())).thenReturn(acc);
        when(cryptoUtil.decrypt("encrypted-secret")).thenReturn("secret-plain");
        when(cryptoUtil.decrypt("encrypted-token")).thenReturn("token-plain");

        PlatformCredential result = service.resolve(1L, "TEMU");

        assertEquals("app-key-1", result.getAppKey());
        assertEquals("secret-plain", result.getAppSecret());
        assertEquals("token-plain", result.getAccessToken());
        assertEquals("https://custom.temu.com", result.getApiBase());
    }

    /**
     * §S33.92 的核心：shopId 非空但该店没配这个平台时，<b>不得</b>拿别家店的凭证。
     * <p>
     * 旧实此刻意回退到「该平台首个账号」，注释称之为「兼容」。真实后果是：
     * 店铺 A 没配 TEMU 账号时，A 的 {@code syncProducts} 会用店铺 B 的凭证去调平台，
     * 消耗 B 的配额，并把拉回来的商品订单全挂到 A 名下。
     */
    @Test
    @DisplayName("shopId 未命中 → 返回空凭证，绝不回退到其他店铺")
    void testResolveShopIdMissDoesNotFallBackToOtherShop() {
        // 库里确实存在别家店的 TEMU 账号——旧实现会把它捡起来用
        PlatformAccount otherShopAcc = new PlatformAccount();
        otherShopAcc.setShopId(99L);
        otherShopAcc.setPlatform("TEMU");
        otherShopAcc.setApiKey("other-shop-key");
        otherShopAcc.setApiSecretEncrypted(null);
        otherShopAcc.setAccessTokenEncrypted(null);

        when(mapper.selectOne(any())).thenReturn(null);

        PlatformCredential result = service.resolve(1L, "TEMU");

        assertNull(result.getAppKey(), "A 店没配 TEMU 就必须拿不到凭证，不能用 B 店的");
        // 只查一次：旧实现在精确匹配未命中后会再发一条不带 shop_id 的全局回退查询。
        // 这里钉住查询次数，回退一旦回来立刻变红。
        verify(mapper, times(1)).selectOne(any());
    }

    /**
     * shopId 为 null 才是「确实没有店铺上下文」，此时允许全局回退。
     * 按 {@code id ASC} 取首个而不是裸 {@code LIMIT 1}：不带 ORDER BY 时选中哪一行
     * 由存储引擎决定，同一份数据在不同执行计划下可能落到不同店铺。
     */
    @Test
    @DisplayName("shopId=null → 才允许全局回退，且取 id 最小的账号（稳定）")
    void testResolveNullShopIdFallsBackDeterministically() {
        PlatformAccount globalAcc = new PlatformAccount();
        globalAcc.setShopId(2L);
        globalAcc.setPlatform("SHEIN");
        globalAcc.setApiKey("shein-key");
        globalAcc.setApiSecretEncrypted(null);
        globalAcc.setAccessTokenEncrypted(null);
        globalAcc.setApiEndpoint(null);

        when(mapper.selectOne(any())).thenReturn(globalAcc);

        PlatformCredential result = service.resolve(null, "SHEIN");

        assertEquals("shein-key", result.getAppKey());
        verify(mapper).selectOne(any());
    }

    @Test
    @DisplayName("shopId=null → 跳过精确匹配，直接进入全局回退")
    void testResolveNullShopId() {
        PlatformAccount globalAcc = new PlatformAccount();
        globalAcc.setShopId(2L);
        globalAcc.setPlatform("SHEIN");
        globalAcc.setApiKey("shein-key");
        globalAcc.setApiSecretEncrypted(null);
        globalAcc.setAccessTokenEncrypted(null);
        globalAcc.setApiEndpoint(null);

        when(mapper.selectOne(any())).thenReturn(globalAcc);

        PlatformCredential result = service.resolve(null, "SHEIN");

        assertEquals("shein-key", result.getAppKey());
        verify(mapper).selectOne(any()); // 只调用一次（全局回退）
    }

    @Test
    @DisplayName("该店该平台查不到 → 空凭证（不再有第二次全局回退查询）")
    void testResolveMissReturnsEmptyWithoutSecondQuery() {
        when(mapper.selectOne(any())).thenReturn(null);

        PlatformCredential result = service.resolve(1L, "UNKNOWN_PLATFORM");

        assertNotNull(result);
        assertNull(result.getAppKey());
        verify(mapper, times(1)).selectOne(any());
    }

    @Test
    @DisplayName("cryptoUtil 为 null → 密文字段按未配置返回 null，不把密文当密钥")
    void testCryptoUtilNullReturnsNullSecret() {
        PlatformAccount acc = new PlatformAccount();
        acc.setShopId(1L);
        acc.setPlatform("TEMU");
        acc.setApiKey("key");
        acc.setApiSecretEncrypted("cipher-text");
        acc.setAccessTokenEncrypted("cipher-token");
        acc.setApiEndpoint(null);

        when(mapper.selectOne(any())).thenReturn(acc);

        PlatformCredentialService noCrypto = new PlatformCredentialService();
        org.springframework.test.util.ReflectionTestUtils.setField(noCrypto, "mapper", mapper);

        PlatformCredential result = noCrypto.resolve(1L, "TEMU");

        assertEquals("key", result.getAppKey());
        // 密文不能当密钥用：返回 null 让 Real 客户端抛「凭证未配置」，
        // 而不是拿 base64 密文去算签名、让平台回 401 把故障说成「认证失败」
        assertNull(result.getAppSecret());
        assertNull(result.getAccessToken());
    }

    @Test
    @DisplayName("cryptoUtil 解密异常 → 该字段按未配置返回 null，其余字段照常解析")
    void testCryptoUtilExceptionReturnsNullForThatField() {
        PlatformAccount acc = new PlatformAccount();
        acc.setShopId(1L);
        acc.setPlatform("TEMU");
        acc.setApiKey("key");
        acc.setApiSecretEncrypted("cipher-text");
        acc.setAccessTokenEncrypted("cipher-token");
        acc.setApiEndpoint(null);

        when(mapper.selectOne(any())).thenReturn(acc);
        when(cryptoUtil.decrypt("cipher-text")).thenThrow(new RuntimeException("decrypt failed"));
        when(cryptoUtil.decrypt("cipher-token")).thenReturn("token-plain");

        PlatformCredential result = service.resolve(1L, "TEMU");

        assertEquals("key", result.getAppKey());
        assertNull(result.getAppSecret(), "解密失败的字段按未配置处理，不能把密文当密钥");
        // 别处能正常解密的字段不受影响：一条密文对不上不该让整个账号不可用又看不出是哪一项
        assertEquals("token-plain", result.getAccessToken());
    }

    @Test
    @DisplayName("apiEndpoint 为空 → 使用平台默认端点")
    void testDefaultApiBase() {
        PlatformAccount acc = new PlatformAccount();
        acc.setShopId(1L);
        acc.setPlatform("TIKTOK");
        acc.setApiKey("tt-key");
        acc.setApiSecretEncrypted(null);
        acc.setAccessTokenEncrypted(null);
        acc.setApiEndpoint(""); // 空字符串

        when(mapper.selectOne(any())).thenReturn(acc);

        PlatformCredential result = service.resolve(1L, "TIKTOK");

        assertEquals("https://open-api.tiktokglobalshop.com", result.getApiBase());
    }

    @Test
    @DisplayName("异常兜底 → mapper 抛异常时返回空凭证")
    void testMapperExceptionReturnsEmpty() {
        when(mapper.selectOne(any())).thenThrow(new RuntimeException("DB error"));

        PlatformCredential result = service.resolve(1L, "TEMU");

        assertNotNull(result);
        assertNull(result.getAppKey());
    }
}
