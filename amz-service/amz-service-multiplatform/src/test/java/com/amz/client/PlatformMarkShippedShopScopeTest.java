package com.amz.client;

import com.amz.credential.PlatformCredential;
import com.amz.credential.PlatformCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发货回传必须用<b>订单所属店铺</b>的平台凭证（§S33.92）。
 *
 * <p>旧实现 {@code markShipped(platformOrderNo, trackingNo)} 内部 {@code cred(null)}，
 * 落到 {@link PlatformCredentialService} 就是「该平台首个账号」。而 service 侧
 * {@code MultiplatformServiceImpl.markShipped} 早就 {@code selectById} 拿到了订单、
 * 也 {@code requireShopOnRow} 校验过归属——shopId 那一刻是已知的，只是被调用链丢掉了。
 * 后果：A 店的发货单用<b>随便某一家店</b>的凭证发出去。
 *
 * <p>shopId 现在是接口必填参数（三个平台接口 + 六个实现都改了签名）。
 * 本测试钉住 Real 客户端确实把它传给了凭证解析，而不是又写回 {@code null}。
 *
 * <p>观察面是 {@code credentialService.resolve(shopId, platform)} 收到的实参：
 * 改回 {@code cred(null)} 会立刻在这里现形。只断言「抛没抛异常」不够——
 * 凭证为空时 Real 客户端也抛 {@code IllegalStateException}，传 null 与传 7L 都可能抛，
 * 那条路径分辨不出区别。
 */
@DisplayName("平台发货回传：必须用订单所属店铺凭证")
class PlatformMarkShippedShopScopeTest {

    private PlatformCredentialService credentialService;

    @BeforeEach
    void stubCredentialService() {
        credentialService = mock(PlatformCredentialService.class);
        // 给一套完整凭证，让请求通过认证检查、走到「发 HTTP」才因 http 未注入而失败。
        // 这条路径与凭证归属无关，不影响断言。
        when(credentialService.resolve(any(), any())).thenReturn(
                new PlatformCredential("ak", "sk", "token", "https://example.invalid"));
    }

    @Test
    @DisplayName("Temu：用调用方给的 shopId 解析凭证，不退回全局账号")
    void temuUsesGivenShopId() {
        TemuRealClient client = new TemuRealClient();
        ReflectionTestUtils.setField(client, "credentialService", credentialService);

        try {
            client.markShipped(7L, "TEMU-ORDER-1", "TRK-1");
        } catch (RuntimeException expected) {
            // http 未注入会在这里抛，本测试不关心请求结果
        }

        verify(credentialService).resolve(7L, "TEMU");
    }

    @Test
    @DisplayName("TikTok：用调用方给的 shopId 解析凭证")
    void tiktokUsesGivenShopId() {
        TikTokRealClient client = new TikTokRealClient();
        ReflectionTestUtils.setField(client, "credentialService", credentialService);

        try {
            client.markShipped(8L, "TT-ORDER-1", "TRK-1");
        } catch (RuntimeException expected) {
            // 同上
        }

        verify(credentialService).resolve(8L, "TIKTOK");
    }

    @Test
    @DisplayName("Shein：用调用方给的 shopId 解析凭证")
    void sheinUsesGivenShopId() {
        SheinRealClient client = new SheinRealClient();
        ReflectionTestUtils.setField(client, "credentialService", credentialService);

        try {
            client.markShipped(9L, "SHEIN-ORDER-1", "TRK-1");
        } catch (RuntimeException expected) {
            // 同上
        }

        verify(credentialService).resolve(9L, "SHEIN");
    }

    /**
     * 参数顺序回归：把 shopId 与 platform 写反同样能编译通过（swap 类型相同时才会，
     * 这里类型不同所以编译器能挡住，但 {@code resolve(getPlatform(), shopId)} 这种
     * 手工写错顺序的改法仍需一条断言兜底）。
     */
    @Test
    @DisplayName("resolve 的两个实参分别是 shopId 与平台标识，不能互换")
    void resolveArgumentsAreShopIdThenPlatform() {
        TemuRealClient client = new TemuRealClient();
        ReflectionTestUtils.setField(client, "credentialService", credentialService);

        try {
            client.markShipped(4242L, "TEMU-ORDER-2", "TRK-2");
        } catch (RuntimeException expected) {
            // 同上
        }

        ArgumentCaptor<Long> shopId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> platform = ArgumentCaptor.forClass(String.class);
        verify(credentialService).resolve(shopId.capture(), platform.capture());
        assertNotNull(shopId.getValue(), "resolve 第一个参数必须是 shopId");
        assertEquals(4242L, shopId.getValue(),
                "shopId 必须是调用方传入的订单所属店铺，实际=" + shopId.getValue());
        assertEquals("TEMU", platform.getValue(),
                "第二个参数必须是平台标识，实际=" + platform.getValue());
    }
}
