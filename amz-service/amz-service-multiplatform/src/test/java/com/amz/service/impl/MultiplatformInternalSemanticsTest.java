package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.OauthAppMapper;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.mapper.PlatformMessageMapper;
import com.amz.mapper.PlatformProductMapper;
import com.amz.model.PlatformAccount;
import com.amz.model.PlatformMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多平台两个动作的「按内部处理」口径。
 * <p>
 * 这一族原来各自有一处会让页面说谎的写法：
 * <ul>
 *   <li>{@code testConnection} 只做端点字符串格式校验（{@code isEndpointWellFormed}
 *       明确不发网络请求），却把账号 {@code status} 写成 ACTIVE/ERROR 并刷新
 *       {@code lastSyncTime}。一个没发过包的检查就这样改变了「账号是否活跃」和
 *       「最近同步时间」两个运维口径；</li>
 *   <li>{@code replyMessage} 只往本地库写一条 OUT 记录，平台和买家都收不到，
 *       而它给这条记录拼的 {@code platformMessageId} 长得像平台真实 ID
 *       （{@code 原ID-REPLY-时间戳}），将来接真实发送时分不清「内部记过」和「平台真回过」。
 *       该列是 NOT NULL，所以必须留个 ID —— 留的就明确标成 LOCAL-REPLY-。</li>
 * </ul>
 * 两个动作的店铺归属守卫不能因为口径改了就被绕过，所以这里同时钉住拒绝路径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("多平台动作的内部处理口径")
class MultiplatformInternalSemanticsTest {

    @Mock
    private PlatformAccountMapper platformAccountMapper;

    @Mock
    private PlatformProductMapper platformProductMapper;

    @Mock
    private PlatformMessageMapper platformMessageMapper;

    @Mock
    private OauthAppMapper oauthAppMapper;

    @InjectMocks
    private MultiplatformServiceImpl service;

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("端点自检无论通过与否，都不改写账号状态与同步时间")
    void testConnectionNeverMutatesAccount() {
        PlatformAccount good = account(11L, 1L, "https://open-api.temu.com/verify", "key-1");
        when(platformAccountMapper.selectById(11L)).thenReturn(good);
        assertTrue(service.testConnection(11L), "格式合规应返回 true");

        PlatformAccount bad = account(12L, 1L, "not-an-endpoint", "key-2");
        when(platformAccountMapper.selectById(12L)).thenReturn(bad);
        assertFalse(service.testConnection(12L), "格式不合规应返回 false");

        // 这两条就是本轮改动的本体：返回结论，但一个字段都不写
        verify(platformAccountMapper, never()).updateById(any(PlatformAccount.class));
        assertEquals("ACTIVE", good.getStatus(), "状态必须还是取出来那个值，没被自检改写");
        assertEquals(null, good.getLastSyncTime(), "自检不该产生「最近同步时间」");
    }

    @Test
    @DisplayName("自检他店账号仍然被拒，且同样不写库")
    void testConnectionStillRejectsForeignShop() {
        when(platformAccountMapper.selectById(13L)).thenReturn(
                account(13L, 2L, "https://open-api.temu.com/verify", "key-3"));

        assertThrows(CodeErrorException.class, () -> service.testConnection(13L));
        verify(platformAccountMapper, never()).updateById(any(PlatformAccount.class));
    }

    @Test
    @DisplayName("回复只记内部备注：OUT 行的 ID 必须自标 LOCAL-REPLY，不像平台真实 ID")
    void replyRecordsInternalNoteWithLocalId() {
        PlatformMessage inbound = message(21L, 1L, "TM-888");
        when(platformMessageMapper.selectById(21L)).thenReturn(inbound);

        assertTrue(service.replyMessage(21L, "已与客户主管沟通"));

        ArgumentCaptor<PlatformMessage> inserted = ArgumentCaptor.forClass(PlatformMessage.class);
        verify(platformMessageMapper).insert(inserted.capture());
        PlatformMessage note = inserted.getValue();
        assertEquals("OUT", note.getDirection());
        assertTrue(note.getPlatformMessageId().startsWith("LOCAL-REPLY-"),
                "内部备注的 ID 必须自带 LOCAL- 标记，实际=" + note.getPlatformMessageId());
        assertFalse(note.getPlatformMessageId().startsWith("TM-888-REPLY-"),
                "不能再拼成看着像平台 ID 派生的串，实际=" + note.getPlatformMessageId());
        assertEquals(1L, note.getShopId());
        // 原消息标为已处理仍要写：这是内部工单状态，不是平台回传
        assertEquals("REPLIED", inbound.getStatus());
        verify(platformMessageMapper).updateById(inbound);
    }

    @Test
    @DisplayName("回复他店消息被拒，并且不写任何一行")
    void replyRejectsForeignShopMessage() {
        when(platformMessageMapper.selectById(22L)).thenReturn(message(22L, 2L, "TM-999"));

        assertThrows(CodeErrorException.class, () -> service.replyMessage(22L, "不该写进去"));
        verify(platformMessageMapper, never()).insert(any(PlatformMessage.class));
        verify(platformMessageMapper, never()).updateById(any(PlatformMessage.class));
    }

    private static PlatformAccount account(Long id, Long shopId, String endpoint, String apiKey) {
        PlatformAccount a = new PlatformAccount();
        a.setId(id);
        a.setShopId(shopId);
        a.setPlatform("TEMU");
        a.setStatus("ACTIVE");
        a.setApiEndpoint(endpoint);
        a.setApiKey(apiKey);
        return a;
    }

    private static PlatformMessage message(Long id, Long shopId, String platformMessageId) {
        PlatformMessage m = new PlatformMessage();
        m.setId(id);
        m.setShopId(shopId);
        m.setPlatform("TEMU");
        m.setPlatformMessageId(platformMessageId);
        m.setDirection("IN");
        m.setStatus("UNREAD");
        return m;
    }
}
