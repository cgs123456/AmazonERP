package com.amz.service.impl;

import com.amz.client.PlatformDataClient;
import com.amz.client.TemuClient;
import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.OauthAppMapper;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.mapper.PlatformMessageMapper;
import com.amz.mapper.PlatformProductMapper;
import com.amz.model.PlatformAccount;
import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;
import com.amz.model.UnifiedOrder;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多平台两个运维动作的真实语义：连接是真探测，回复是真发送。
 * <p>
 * 这一族原来各自有一处会让页面说谎的写法：
 * <ul>
 *   <li>{@code testConnection} 只做端点字符串格式校验（不发网络请求），却把账号
 *       {@code status} 写成 ACTIVE/ERROR 并刷新 {@code lastSyncTime}。一个没发过包的检查
 *       就这样改变了「账号是否活跃」和「最近同步时间」两个运维口径。
 *       现在它是一次<b>真探测</b>：复用各家已实现的订单读发一次已鉴权请求，
 *       状态由平台是否回话决定；但探测仍然不是同步，所以 {@code lastSyncTime} 一个字节都不写。</li>
 *   <li>{@code replyMessage} 曾经只往本地库写一条 OUT 记录就把原消息标成 REPLIED，
 *       平台和买家都收不到，而它拼的 {@code platformMessageId} 长得像平台真实 ID。
 *       现在它先真发（{@code PlatformDataClient#sendMessage}），拿到平台消息 ID 才写本地两行；
 *       没接入的平台一律抛「未接入」，一行都不写。</li>
 * </ul>
 * 两个动作的店铺归属守卫不能因为口径改了就被绕过，所以这里同时钉住拒绝路径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("多平台动作：真探测与真发送")
class MultiplatformInternalSemanticsTest {

    @Mock
    private PlatformAccountMapper platformAccountMapper;

    @Mock
    private PlatformProductMapper platformProductMapper;

    @Mock
    private PlatformMessageMapper platformMessageMapper;

    @Mock
    private OauthAppMapper oauthAppMapper;

    @Mock
    private TemuClient temuClient;

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

    // ==================== 探测契约本身 ====================

    @Test
    @DisplayName("探测复用订单读：平台回话即成功，抛错即失败，不把异常抛给调用方")
    void probeReusesTheAuthenticatedOrderRead() {
        assertTrue(orderReadReturning(List.of(new UnifiedOrder())).probeConnection(1L),
                "订单读成功就等于连通");
        assertTrue(orderReadReturning(List.of()).probeConnection(1L),
                "空订单列表也是已鉴权成功，不能当成没连通");

        RuntimeException noCredential = new IllegalStateException("Temu 凭证未配置");
        assertFalse(orderReadThrowing(noCredential).probeConnection(1L),
                "凭证缺失应判为没连通");
        assertFalse(orderReadThrowing(new RuntimeException("Temu fetchRecentOrders failed: 401")).probeConnection(1L),
                "签名被拒应判为没连通");
    }

    // ==================== testConnection ====================

    @Test
    @DisplayName("探测通过才写 ACTIVE，且探测不改写「最近同步时间」")
    void testConnectionWritesStatusFromRealProbe() {
        PlatformAccount account = account(11L, 1L, "TEMU");
        when(platformAccountMapper.selectById(11L)).thenReturn(account);
        when(temuClient.probeConnection(1L)).thenReturn(true);

        assertTrue(service.testConnection(11L));

        ArgumentCaptor<PlatformAccount> saved = ArgumentCaptor.forClass(PlatformAccount.class);
        verify(platformAccountMapper).updateById(saved.capture());
        assertEquals("ACTIVE", saved.getValue().getStatus());
        assertNull(saved.getValue().getLastSyncTime(),
                "探测不是同步：lastSyncTime 只能由真实同步任务推进");
    }

    @Test
    @DisplayName("探测失败写 ERROR，而不是保留上一次的 ACTIVE")
    void testConnectionMarksErrorWhenProbeFails() {
        PlatformAccount account = account(12L, 1L, "TEMU");
        when(platformAccountMapper.selectById(12L)).thenReturn(account);
        when(temuClient.probeConnection(1L)).thenReturn(false);

        assertFalse(service.testConnection(12L));

        ArgumentCaptor<PlatformAccount> saved = ArgumentCaptor.forClass(PlatformAccount.class);
        verify(platformAccountMapper).updateById(saved.capture());
        assertEquals("ERROR", saved.getValue().getStatus());
        assertNull(saved.getValue().getLastSyncTime(), "失败路径同样不能碰同步时间");
    }

    @Test
    @DisplayName("亚马逊账号点名拒绝探测而不是标成 ERROR：没做这条检查和探测失败是两回事")
    void testConnectionRefusesUnsupportedPlatformInsteadOfMarkingItBroken() {
        when(platformAccountMapper.selectById(14L)).thenReturn(account(14L, 1L, "AMAZON"));

        AttrIsNullException refused = assertThrows(AttrIsNullException.class,
                () -> service.testConnection(14L));
        assertTrue(refused.getMessage().contains("不支持的平台"),
                "拒绝理由要能读出来，实际=" + refused.getMessage());
        verify(platformAccountMapper, never()).updateById(any(PlatformAccount.class));
    }

    @Test
    @DisplayName("探测他店账号仍然被拒，且完全不写库")
    void testConnectionStillRejectsForeignShop() {
        when(platformAccountMapper.selectById(13L)).thenReturn(account(13L, 2L, "TEMU"));

        assertThrows(CodeErrorException.class, () -> service.testConnection(13L));
        verify(platformAccountMapper, never()).updateById(any(PlatformAccount.class));
    }

    // ==================== replyMessage ====================

    @Test
    @DisplayName("回复是真发送：先拿到平台消息 ID，之后才写本地两行")
    void replySendsFirstAndRecordsOnlyWhatThePlatformAccepted() {
        PlatformMessage inbound = message(21L, 1L, "TM-888");
        when(platformMessageMapper.selectById(21L)).thenReturn(inbound);
        ArgumentCaptor<PlatformMessage> draft = ArgumentCaptor.forClass(PlatformMessage.class);
        when(temuClient.sendMessage(draft.capture())).thenReturn("TM-OUT-777");

        assertTrue(service.replyMessage(21L, "已与客户主管沟通"));

        PlatformMessage sent = draft.getValue();
        assertEquals("OUT", sent.getDirection(), "发出去的必须是 OUT 方向");
        assertEquals(1L, sent.getShopId());
        assertEquals("已与客户主管沟通", sent.getContent());
        assertEquals("Re: When will it ship?", sent.getSubject());

        ArgumentCaptor<PlatformMessage> inserted = ArgumentCaptor.forClass(PlatformMessage.class);
        verify(platformMessageMapper).insert(inserted.capture());
        PlatformMessage note = inserted.getValue();
        // 这条 OUT 记录现在是平台确认的副本：ID 就是平台给的，不再自造 LOCAL-REPLY-
        assertEquals("TM-OUT-777", note.getPlatformMessageId());
        assertFalse(note.getPlatformMessageId().startsWith("LOCAL-REPLY-"),
                "自造 ID 会把「内部记过」冒充成平台回过");
        assertEquals("REPLIED", inbound.getStatus());

        // 顺序就是这条链路的本体：平台没收之前，一行都不许写
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(temuClient, platformMessageMapper);
        order.verify(temuClient).sendMessage(any(PlatformMessage.class));
        order.verify(platformMessageMapper).updateById(inbound);
        order.verify(platformMessageMapper).insert(any(PlatformMessage.class));
    }

    @Test
    @DisplayName("平台侧没接入就显式拒绝：不写 REPLIED，也不留任何一行回复")
    void replyRefusesWithoutWritingWhenPlatformSendIsMissing() {
        when(platformMessageMapper.selectById(21L)).thenReturn(message(21L, 1L, "TM-888"));
        when(temuClient.sendMessage(any(PlatformMessage.class)))
                .thenThrow(new UnsupportedOperationException(
                        "TEMU 平台的买家站内信发送接口尚未接入"));

        assertThrows(UnsupportedOperationException.class, () -> service.replyMessage(21L, "hi"));
        verify(platformMessageMapper, never()).insert(any(PlatformMessage.class));
        verify(platformMessageMapper, never()).updateById(any(PlatformMessage.class));
    }

    @Test
    @DisplayName("回复他店消息被拒，并且不写任何一行")
    void replyRejectsForeignShopMessage() {
        when(platformMessageMapper.selectById(22L)).thenReturn(message(22L, 2L, "TM-999"));

        assertThrows(CodeErrorException.class, () -> service.replyMessage(22L, "不该写进去"));
        verify(platformMessageMapper, never()).insert(any(PlatformMessage.class));
        verify(platformMessageMapper, never()).updateById(any(PlatformMessage.class));
    }

    /** 探测契约的两种实现：只关心默认方法怎么把订单读的结果变成 true/false。 */
    private static PlatformDataClient orderReadReturning(List<UnifiedOrder> orders) {
        return new StubClient(orders, null);
    }

    private static PlatformDataClient orderReadThrowing(RuntimeException failure) {
        return new StubClient(List.of(), failure);
    }

    private static final class StubClient implements PlatformDataClient {
        private final List<UnifiedOrder> orders;
        private final RuntimeException failure;

        StubClient(List<UnifiedOrder> orders, RuntimeException failure) {
            this.orders = orders;
            this.failure = failure;
        }

        @Override
        public List<PlatformProduct> fetchProducts(Long shopId) {
            return List.of();
        }

        @Override
        public List<PlatformMessage> fetchMessages(Long shopId) {
            return List.of();
        }

        @Override
        public List<PlatformInventory> fetchInventory(Long shopId) {
            return List.of();
        }

        @Override
        public List<UnifiedOrder> fetchRecentOrders(Long shopId) {
            if (failure != null) {
                throw failure;
            }
            return orders;
        }

        @Override
        public String sendMessage(PlatformMessage outbound) {
            return "STUB-OUT-1";
        }
    }

    private static PlatformAccount account(Long id, Long shopId, String platform) {
        PlatformAccount a = new PlatformAccount();
        a.setId(id);
        a.setShopId(shopId);
        a.setPlatform(platform);
        a.setStatus("ACTIVE");
        a.setApiEndpoint("https://open-api.temu.com");
        a.setApiKey("key-1");
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
        m.setSubject("When will it ship?");
        return m;
    }
}
