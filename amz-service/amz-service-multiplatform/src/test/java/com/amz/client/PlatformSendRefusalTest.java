package com.amz.client;

import com.amz.model.PlatformMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「真实发送」在两个档位上的口径必须是可机器区分的。
 * <p>
 * {@code replyMessage} 现在拿平台返回的消息 ID 才写本地记账，所以两端各自的失败模式都要钉住：
 * <ul>
 *   <li>Real 客户端：三家都还没接买家站内信发送的 Open API method，
 *       必须抛「未接入」而不是回一个假 ID —— 假 ID 会让客服页面显示「已回复」而买家什么都没收到；</li>
 *   <li>Mock 客户端：离线演示要能走通，但返回的 ID 必须自带 {@code MOCK-} 前缀，
 *       让人一眼看出这条「成功」是模拟档给的，不是平台给的。</li>
 * </ul>
 * 直接 new 客户端即可：{@code unimplemented()} 只用到平台名，不需要 HTTP 与凭证注入。
 * 本测试与客户端同包，所以能读到 {@code protected getPlatform()}。
 */
@DisplayName("平台站内信发送：真实档显式未接入，模拟档自带 MOCK 标")
class PlatformSendRefusalTest {

    private static PlatformMessage draft() {
        PlatformMessage m = new PlatformMessage();
        m.setShopId(1L);
        m.setDirection("OUT");
        m.setContent("测试正文");
        return m;
    }

    private static String declaredPlatform(Object client) {
        return ((AbstractPlatformClient) client).getPlatform();
    }

    private static void assertRefusesNamedly(Object client, String platform) {
        assertEquals(platform, declaredPlatform(client),
                client.getClass().getSimpleName() + " 声明的平台名不对");
        UnsupportedOperationException refused = assertThrows(
                UnsupportedOperationException.class,
                () -> ((PlatformDataClient) client).sendMessage(draft()),
                client.getClass().getSimpleName() + " 不该在没有 method 依据时假装发送成功");
        assertTrue(refused.getMessage().contains(platform),
                "理由要能看出是哪家平台，实际=" + refused.getMessage());
        assertTrue(refused.getMessage().contains("站内信发送"),
                "理由要指名缺的是哪个能力，实际=" + refused.getMessage());
    }

    @Test
    @DisplayName("三家真实客户端都点名拒绝发送")
    void realClientsRefuseInsteadOfInventingAPlatformId() {
        assertRefusesNamedly(new TemuRealClient(), "TEMU");
        assertRefusesNamedly(new TikTokRealClient(), "TIKTOK");
        assertRefusesNamedly(new SheinRealClient(), "SHEIN");
    }

    private static void assertMockId(Object client) {
        String id = ((PlatformDataClient) client).sendMessage(draft());
        assertTrue(id.startsWith("MOCK-OUT-"),
                client.getClass().getSimpleName() + " 的模拟 ID 必须自带 MOCK- 前缀，实际=" + id);
    }

    @Test
    @DisplayName("三家模拟客户端给的是 MOCK- 前缀 ID，不与平台真实 ID 同形")
    void mockClientsLabelTheirFakeId() {
        assertMockId(new TemuMockClient());
        assertMockId(new TikTokMockClient());
        assertMockId(new SheinMockClient());
    }
}
