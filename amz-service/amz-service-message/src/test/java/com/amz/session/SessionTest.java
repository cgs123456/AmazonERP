package com.amz.session;

import io.netty.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * WebSocket 会话管理单元测试（纯 Mockito，不依赖 Netty 服务器）。
 * <p>
 * 验证 Session 的 bind/unbind/getUserId/getChannel 四条核心链路，
 * 覆盖正常绑定、解绑后清除、覆盖绑定等场景。
 */
@DisplayName("WebSocket 会话管理单元测试")
class SessionTest {

    private static final int USER_A = 80001;
    private static final int USER_B = 80002;

    private final Channel channelA = mock(Channel.class);
    private final Channel channelB = mock(Channel.class);

    @AfterEach
    void cleanup() {
        Session.unbind(USER_A, channelA);
        Session.unbind(USER_A, channelB);
        Session.unbind(USER_B, channelB);
    }

    @Test
    @DisplayName("bind → getUserId 和 getChannel 能正确返回")
    void testBindAndGet() {
        Session.bind(USER_A, channelA);

        assertEquals(USER_A, Session.getUserId(channelA), "channel 应映射到 userId");
        assertEquals(channelA, Session.getChannel(USER_A), "userId 应映射到 channel");
    }

    @Test
    @DisplayName("unbind → 映射关系清除")
    void testUnbindClearsMapping() {
        Session.bind(USER_A, channelA);
        Session.unbind(USER_A, channelA);

        assertNull(Session.getUserId(channelA), "解绑后 getUserId 应为 null");
        assertNull(Session.getChannel(USER_A), "解绑后 getChannel 应为 null");
    }

    @Test
    @DisplayName("同一用户重新绑定新 channel → 覆盖旧映射")
    void testRebindOverwritesChannel() {
        Session.bind(USER_A, channelA);
        Session.bind(USER_A, channelB);

        assertEquals(channelB, Session.getChannel(USER_A), "应映射到最新的 channel");
    }

    @Test
    @DisplayName("未绑定的 channel → getUserId 返回 null")
    void testGetUserIdUnboundChannel() {
        Channel unknown = mock(Channel.class);
        assertNull(Session.getUserId(unknown), "未绑定 channel 应返回 null");
    }

    @Test
    @DisplayName("未绑定的 userId → getChannel 返回 null")
    void testGetChannelUnboundUser() {
        assertNull(Session.getChannel(99999), "未绑定 userId 应返回 null");
    }

    @Test
    @DisplayName("旧连接关闭事件不得误删同用户新登录的连接")
    void testStaleUnbindDoesNotKillNewSession() {
        Session.bind(USER_A, channelA);
        Session.bind(USER_A, channelB);
        // 旧连接 channelA 后到达的关闭事件
        Session.unbind(USER_A, channelA);

        assertEquals(channelB, Session.getChannel(USER_A), "新连接不应被旧关闭事件误删");
        assertEquals(USER_A, Session.getUserId(channelB), "新连接反向映射应保留");
    }

    @Test
    @DisplayName("重绑后旧 channel 反向映射应被清理（无幽灵条目）")
    void testRebindCleansReverseMapping() {
        Session.bind(USER_A, channelA);
        Session.bind(USER_A, channelB);

        assertNull(Session.getUserId(channelA), "旧 channel 反向映射应被清理");
    }

    @Test
    @DisplayName("多用户绑定 → 各自独立互不干扰")
    void testMultipleUsersIndependent() {
        Session.bind(USER_A, channelA);
        Session.bind(USER_B, channelB);

        assertEquals(USER_A, Session.getUserId(channelA));
        assertEquals(USER_B, Session.getUserId(channelB));
        assertEquals(channelA, Session.getChannel(USER_A));
        assertEquals(channelB, Session.getChannel(USER_B));

        Session.unbind(USER_A, channelA);
        assertNull(Session.getUserId(channelA));
        assertEquals(USER_B, Session.getUserId(channelB), "解绑 A 不应影响 B");
    }
}
