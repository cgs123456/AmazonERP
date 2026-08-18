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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlatformCredentialService 单元测试。
 * <p>
 * 覆盖 Item6（多租户凭证建模）的核心行为：
 * - mapper/cryptoUtil 缺失时诚实返回空凭证
 * - 按 (shopId, platform) 精确解析
 * - 全局回退（无 shopId 或指定 shopId 未命中时）
 * - 解密失败/缺失时原文回退
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

    @Test
    @DisplayName("shopId 未命中 → 回退到全局首个账号")
    void testResolveFallbackToGlobal() {
        PlatformAccount globalAcc = new PlatformAccount();
        globalAcc.setShopId(99L);
        globalAcc.setPlatform("TIKTOK");
        globalAcc.setApiKey("global-key");
        globalAcc.setApiSecretEncrypted(null);
        globalAcc.setAccessTokenEncrypted(null);
        globalAcc.setApiEndpoint(null);

        // shopId=1 未命中，返回 null
        when(mapper.selectOne(any())).thenReturn(null, globalAcc);

        PlatformCredential result = service.resolve(1L, "TIKTOK");

        assertEquals("global-key", result.getAppKey());
        assertEquals("https://open-api.tiktokglobalshop.com", result.getApiBase()); // 默认端点
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
    @DisplayName("全局也未命中 → 返回空凭证")
    void testResolveGlobalMissReturnsEmpty() {
        when(mapper.selectOne(any())).thenReturn(null, null);

        PlatformCredential result = service.resolve(1L, "UNKNOWN_PLATFORM");

        assertNotNull(result);
        assertNull(result.getAppKey());
    }

    @Test
    @DisplayName("cryptoUtil 为 null → 明文回退，不阻断主链路")
    void testCryptoUtilNullPlainTextFallback() {
        PlatformAccount acc = new PlatformAccount();
        acc.setShopId(1L);
        acc.setPlatform("TEMU");
        acc.setApiKey("key");
        acc.setApiSecretEncrypted("cipher-text");
        acc.setAccessTokenEncrypted("cipher-token");
        acc.setApiEndpoint(null);

        when(mapper.selectOne(any())).thenReturn(acc);

        // 不注入 cryptoUtil（@InjectMocks 会自动设为 null）
        PlatformCredentialService noCrypto = new PlatformCredentialService();
        // 通过反射注入 mapper
        org.springframework.test.util.ReflectionTestUtils.setField(noCrypto, "mapper", mapper);

        PlatformCredential result = noCrypto.resolve(1L, "TEMU");

        assertEquals("key", result.getAppKey());
        assertEquals("cipher-text", result.getAppSecret()); // 原文回退
        assertEquals("cipher-token", result.getAccessToken());
    }

    @Test
    @DisplayName("cryptoUtil 解密异常 → 明文回退，不阻断主链路")
    void testCryptoUtilExceptionPlainTextFallback() {
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
        assertEquals("cipher-text", result.getAppSecret()); // 解密异常，原文回退
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
