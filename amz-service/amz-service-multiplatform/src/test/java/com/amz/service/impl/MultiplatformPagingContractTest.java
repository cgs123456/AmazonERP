package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.OauthAppMapper;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.mapper.PlatformInventoryMapper;
import com.amz.mapper.PlatformMessageMapper;
import com.amz.mapper.PlatformProductMapper;
import com.amz.mapper.WebhookEventMapper;
import com.amz.model.OauthApp;
import com.amz.model.PlatformAccount;
import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;
import com.amz.model.WebhookEvent;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多平台六个列表的 keyset 分页契约。
 *
 * <p>动因与广告日报、订单列表完全同一类：这些接口原来是 {@code selectList} 不带 LIMIT，
 * 消息/库存/商品/Webhook 都是会被同步持续写大的表，一次请求可以把整店数据读进内存；
 * 而 HTTP 200 + 数组，与「确实只有这么多」在调用方看来毫无区别。
 *
 * <p>按时间排序的列表（消息/库存/Webhook）排序键不唯一，所以游标必须是 (时间, id) 复合键；
 * 只按时间翻页会在同一秒内多行时漏行或重复。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("多平台列表 keyset 分页契约")
class MultiplatformPagingContractTest {

    @Mock
    private PlatformAccountMapper platformAccountMapper;
    @Mock
    private PlatformProductMapper platformProductMapper;
    @Mock
    private PlatformMessageMapper platformMessageMapper;
    @Mock
    private PlatformInventoryMapper platformInventoryMapper;
    @Mock
    private WebhookEventMapper webhookEventMapper;
    @Mock
    private OauthAppMapper oauthAppMapper;

    @InjectMocks
    private MultiplatformServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        for (Class<?> entity : List.of(PlatformAccount.class, PlatformProduct.class,
                PlatformMessage.class, PlatformInventory.class, WebhookEvent.class, OauthApp.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

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
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("账号列表按 id 倒序分页，探测 size+1")
    void accountsPagedById() {
        when(platformAccountMapper.selectList(any())).thenReturn(List.of(
                account(12L), account(11L), account(10L)));

        PageResult<PlatformAccount> page = service.listAccounts(1L, PageRequest.of(2, null));

        assertEquals(List.of(12L, 11L), page.items().stream().map(PlatformAccount::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(11L), page.nextCursor());
        String sql = captured(platformAccountMapper).getCustomSqlSegment();
        assertTrue(sql.contains("ORDER BY id DESC") || sql.contains("id DESC"), sql);
        assertTrue(sql.contains("LIMIT 3"), sql);
    }

    @Test
    @DisplayName("账号列表返回前抹掉凭证列：读取接口不该把 apiKey 与密文回传")
    void accountsAreRedacted() {
        PlatformAccount row = account(12L);
        row.setApiKey("AK-plain-secret");
        row.setApiSecretEncrypted("cipher");
        row.setAccessTokenEncrypted("cipher");
        row.setRefreshTokenEncrypted("cipher");
        when(platformAccountMapper.selectList(any())).thenReturn(List.of(row));

        PlatformAccount returned = service.listAccounts(1L, PageRequest.first(20)).items().get(0);

        assertTrue(isBlankOrNull(returned.getApiKey()));
        assertTrue(isBlankOrNull(returned.getApiSecretEncrypted()));
        assertTrue(isBlankOrNull(returned.getAccessTokenEncrypted()));
        assertTrue(isBlankOrNull(returned.getRefreshTokenEncrypted()));
        // 业务列不能被顺手抹掉：页面要靠这些判断账号状态
        assertEquals("Store 12", returned.getStoreName());
        assertEquals("ACTIVE", returned.getStatus());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("消息状态按字符串过滤并作为绑定参数下推，不是拼进 SQL")
    void messageStatusFilterBindsString() {
        when(platformMessageMapper.selectList(any())).thenReturn(List.of());

        service.listMessages(1L, null, "UNREAD", PageRequest.first(20));

        String sql = sqlOf(captured(platformMessageMapper));
        // 真正的守卫是签名：status 参数已经是 String（原来是 Integer，会把 varchar 列
        // 与数字做隐式数值比较，条件形同失效）。这里只证明它是参数化下推、且值没被拼进 SQL。
        assertTrue(sql.contains("status = #{ew.paramNameValuePairs."), sql);
        assertFalse(sql.contains("UNREAD"), "值不该出现在 SQL 文本里：" + sql);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("商品列表按 id 倒序分页并保留平台过滤")
    void productsPaged() {
        when(platformProductMapper.selectList(any())).thenReturn(List.of(product(5L), product(4L)));

        // 带游标调用：这样绑定参数一定能在 paramNameValuePairs 里读到（MP 只在有嵌套 and 时回填顶层值）
        PageResult<PlatformProduct> page = service.listProducts(
                1L, "TEMU", PageRequest.of(20, PageRequest.encodeCursor(9L)));

        assertEquals(2, page.items().size());
        assertFalse(page.truncated());
        LambdaQueryWrapper<?> wrapper = captured(platformProductMapper);
        String sql = sqlOf(wrapper);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("TEMU"),
                "params=" + wrapper.getParamNameValuePairs() + " sql=" + sql);
        assertTrue(sql.contains("id <"), sql);
        assertTrue(sql.contains("LIMIT 21"), sql);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("消息列表用 (receive_time,id) 复合游标：同一秒多行不会漏也不会重")
    void messagesUseCompositeCursor() {
        when(platformMessageMapper.selectList(any())).thenReturn(List.of(message(31L), message(30L)));

        PageResult<PlatformMessage> page = service.listMessages(
                1L, null, null, PageRequest.of(20, PageRequest.encodeCursor("2026-10-01T10:00:00|31")));

        LambdaQueryWrapper<?> wrapper = captured(platformMessageMapper);
        String sql = sqlOf(wrapper);
        assertTrue(sql.contains("receive_time <"), sql);
        assertTrue(sql.contains("id <"), sql);
        assertTrue(sql.contains("LIMIT 21"), sql);
        assertEquals(2, page.items().size());
    }

    @Test
    @DisplayName("消息游标缺 id 时按非法参数拒绝，而不是悄悄退回首页")
    void messagesRejectHalfCursor() {
        PageRequest bad = PageRequest.of(20, PageRequest.encodeCursor("2026-10-01T10:00:00"));

        assertThrows(com.amz.exception.InvalidParamException.class,
                () -> service.listMessages(1L, null, null, bad));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("库存列表用 (snapshot_time,id) 复合游标")
    void inventoryUsesCompositeCursor() {
        when(platformInventoryMapper.selectList(any())).thenReturn(List.of(inventory(7L)));

        service.listPlatformInventory(1L, "TEMU", PageRequest.of(10, null));

        String sql = sqlOf(captured(platformInventoryMapper));
        assertTrue(sql.contains("snapshot_time"), sql);
        assertTrue(sql.contains("LIMIT 11"), sql);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("Webhook 列表用 (create_time,id) 复合游标，状态过滤保留")
    void webhookUsesCompositeCursor() {
        when(webhookEventMapper.selectList(any())).thenReturn(List.of(webhook(3L)));

        service.listWebhookEvents(1L, "FAILED", PageRequest.of(10, null));

        LambdaQueryWrapper<?> wrapper = captured(webhookEventMapper);
        String sql = sqlOf(wrapper);
        assertTrue(sql.contains("create_time"), sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("FAILED"), sql);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    @DisplayName("ISV 应用列表按 id 倒序分页")
    void appsPaged() {
        when(oauthAppMapper.selectList(any())).thenReturn(List.of(oauthApp(2L), oauthApp(1L)));

        PageResult<OauthApp> page = service.listApps(1L, PageRequest.of(1, null));

        assertEquals(1, page.items().size());
        assertTrue(page.truncated());
        assertTrue(sqlOf(captured(oauthAppMapper)).contains("LIMIT 2"), sqlOf(captured(oauthAppMapper)));
    }

    @Test
    @DisplayName("库存聚合返回的是计算时刻，字段名不能长得像平台快照时间")
    void aggregatedInventoryLabelsComputedTime() {
        when(platformInventoryMapper.selectList(any())).thenReturn(List.of(inventory(7L)));

        var result = service.aggregatedInventory(1L);

        assertTrue(result.containsKey("computedAt"), "必须改名：aggregate 的时间不是平台快照时间");
        assertFalse(result.containsKey("snapshotTime"),
                "snapshotTime 会被读成平台侧快照时间，实际只是本次聚合的时刻");
        assertEquals(1L, result.get("shopId"));
    }

    // ==================== 夹具 ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<?> captured(Object mapper) {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify((com.baomidou.mybatisplus.core.mapper.BaseMapper<?>) mapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static String sqlOf(LambdaQueryWrapper<?> wrapper) {
        return wrapper.getCustomSqlSegment();
    }

    private static boolean isBlankOrNull(String value) {
        return value == null || value.isEmpty();
    }

    private static PlatformAccount account(Long id) {
        PlatformAccount a = new PlatformAccount();
        a.setId(id);
        a.setShopId(1L);
        a.setPlatform("TEMU");
        a.setStoreName("Store " + id);
        a.setStatus("ACTIVE");
        return a;
    }

    private static PlatformProduct product(Long id) {
        PlatformProduct p = new PlatformProduct();
        p.setId(id);
        p.setShopId(1L);
        p.setPlatform("TEMU");
        p.setPlatformProductId("PP-" + id);
        return p;
    }

    private static PlatformMessage message(Long id) {
        PlatformMessage m = new PlatformMessage();
        m.setId(id);
        m.setShopId(1L);
        m.setReceiveTime(LocalDateTime.of(2026, 10, 1, 10, 0));
        return m;
    }

    private static PlatformInventory inventory(Long id) {
        PlatformInventory i = new PlatformInventory();
        i.setId(id);
        i.setShopId(1L);
        i.setPlatform("TEMU");
        i.setSku("SKU-" + id);
        i.setAvailableQty(5);
        i.setSnapshotTime(LocalDateTime.of(2026, 10, 1, 8, 0));
        return i;
    }

    private static WebhookEvent webhook(Long id) {
        WebhookEvent e = new WebhookEvent();
        e.setId(id);
        e.setShopId(1L);
        e.setPlatform("TEMU");
        e.setStatus("FAILED");
        e.setCreateTime(LocalDateTime.of(2026, 10, 1, 9, 0));
        return e;
    }

    private static OauthApp oauthApp(Long id) {
        OauthApp app = new OauthApp();
        app.setId(id);
        app.setOwnerShopId(1L);
        app.setAppName("app-" + id);
        return app;
    }
}
