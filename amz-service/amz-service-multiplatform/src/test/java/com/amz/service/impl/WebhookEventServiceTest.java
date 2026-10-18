package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.result.PageRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.mapper.WebhookEventMapper;
import com.amz.model.PlatformAccount;
import com.amz.model.WebhookEvent;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
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

import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Webhook 事件落库后的状态迁移。
 *
 * <p>动因：DDL 写的是 {@code RECEIVED/PROCESSED/FAILED}，而实现只在异常分支改 status，
 * 成功路径把 {@code RECEIVED} 原样写回——于是 {@code PROCESSED} 永远不会出现，
 * 按状态筛选或做统计的页面会以为所有事件都还堵着。这条差异不体现在任何报错上，
 * 只能靠断言钉住。
 *
 * <p>§S33.90 随 {@link WebhookEventService} 从 {@link MultiplatformServiceImpl} 拆出而改名，
 * 断言一字未改——抽类只该搬代码，不该顺手改行为。webhook 是唯一不带用户 JWT 的入口，
 * 这些断言守护的正是那条无用户上下文的写路径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Webhook 事件状态迁移")
class WebhookEventServiceTest {

    @Mock
    private WebhookEventMapper webhookEventMapper;

    @Mock
    private PlatformAccountMapper platformAccountMapper;

    @InjectMocks
    private WebhookEventService service;

    /**
     * 纯 Mockito 测试没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时会抛
     * “can not find lambda cache for this entity”。注册元信息后断言才能读到
     * 真实拼出来的查询条件（§S33.81 同款处理）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlatformAccount.class);
        TableInfoHelper.initTableInfo(assistant, WebhookEvent.class);
    }

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        ReflectionTestUtils.setField(service, "temuWebhookSecret", "test-secret");
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("处理未抛异常时状态要变成 PROCESSED，并留下人话结果")
    void successMarksProcessed() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);

        WebhookEvent saved = service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-1", "{}", 1L, sign("{}"));

        assertNotNull(saved);
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventMapper, atLeastOnce()).updateById(captor.capture());
        WebhookEvent finalState = captor.getValue();
        assertEquals("PROCESSED", finalState.getStatus());
        assertNotNull(finalState.getProcessResult());
        assertNotNull(finalState.getProcessTime());
        // 幂等：同 eventId 第二次直接返回 null，不重复落库
        verify(webhookEventMapper).insert(any(WebhookEvent.class));
    }

    /**
     * §S33.90：入库后落终态失败时，异常必须外抛。
     * <p>
     * 这条断言的来由是**变异存活**：把 {@code @Transactional} 从 receiveWebhook 上删掉，
     * 原有 10 项测试全绿——事务注解不在方法签名里，搬迁时最容易丢，而没有任何测试
     * 拦得住。真正验证「insert 与 updateById 同事务回滚」需要 Spring 事务测试支持，
     * 本模块测试全是纯 Mockito；这里钉住异常穿透这一可观测部分：
     * 它不外抛，事件就永远停在 RECEIVED，而调用方以为成功了。
     */
    @Test
    @DisplayName("落终态失败时异常外抛，不吞成假成功")
    void finalStateFailurePropagates() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);
        doThrow(new IllegalStateException("db down"))
                .when(webhookEventMapper).updateById(any(WebhookEvent.class));

        assertThrows(IllegalStateException.class,
                () -> service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-TX", "{}", 1L, sign("{}")),
                "updateById 抛异常时必须外抛：吞掉它，事件停在 RECEIVED，调用方以为成功");
    }

    @Test
    @DisplayName("事件类型为空时按失败记录，而不是把异常抛回平台回调方")
    void handlerFailureMarksFailed() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);

        WebhookEvent saved = service.receiveWebhook("TEMU", null, "EV-2", "{}", 1L, sign("{}"));

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventMapper, atLeastOnce()).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertNotNull(saved);
    }

    @Test
    @DisplayName("缺签名头 → 验签拒绝，不做幂等查询也不落库")
    void missingSignatureRejected() {
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-3", "{}", 1L, null));
        assertEquals("Webhook 验签失败：缺少 X-Signature 签名头", ex.getMessage());
        verify(webhookEventMapper, never()).insert(any(WebhookEvent.class));
    }

    @Test
    @DisplayName("签名不匹配 → 验签拒绝")
    void wrongSignatureRejected() {
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-4", "{}", 1L, "deadbeef"));
        assertEquals("Webhook 验签失败：签名不匹配", ex.getMessage());
        verify(webhookEventMapper, never()).insert(any(WebhookEvent.class));
    }

    @Test
    @DisplayName("未配置密钥的平台 → fail-closed 拒绝（显式拒绝优于假成功）")
    void unconfiguredPlatformRejected() {
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.receiveWebhook("AMAZON", "ORDER_CREATED", "EV-5", "{}", 1L, sign("{}")));
        assertEquals("Webhook 验签失败：平台 AMAZON 未配置验签密钥（multiplatform.webhook.secret.amazon）",
                ex.getMessage());
        verify(webhookEventMapper, never()).insert(any(WebhookEvent.class));
    }

    @Test
    @DisplayName("平台账号未录入且未传 shopId → 业务拒绝而非运行时异常（2026-10-07 回环验收发现）")
    void noPlatformAccountRejectedAsBusinessError() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);
        when(platformAccountMapper.selectList(any())).thenReturn(List.of());

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-6", "{}", null, sign("{}")));
        // 明确指向可修复原因（录入 amz_platform_account），而不是被兜底成「服务器内部错误」
        assertTrue(ex.getMessage().contains("尚未录入可用店铺账号"), ex.getMessage());
        verify(webhookEventMapper, never()).insert(any(WebhookEvent.class));
    }

    /**
     * §S33.88：同平台多店铺时，不带 shopId 的回调必须被拒而不是被猜。
     * <p>
     * 旧实现 {@code orderByDesc(createTime).last("LIMIT 1")} 取「最新创建的账号」，
     * 把回调硬塞给任意一个店铺。本仓里这是<b>必然</b>发生而非边角情况：
     * 控制器传进来的 shopId 恒为 null（webhook 是免认证白名单入口，无用户上下文），
     * 反查是唯一路径；而 {@code uk_shop_platform} 允许同平台挂多店
     * （合成数据里 SHOPIFY 同时属于 4 个店铺），验签密钥又是平台级、签名里
     * 不含任何可区分店铺的信息。
     * <p>
     * 后果是跨租户数据污染：A 店的订单事件记到 B 店名下，A 店运营看不到自己的回调，
     * 两边都收不到错误——看起来完全成功。所以这里宁可 400 让平台侧补 shopId。
     */
    @Test
    @DisplayName("同平台多店铺且回调未带 shopId → 拒绝落库，不按创建时间猜一个店")
    void ambiguousShopIsRejectedNotGuessed() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);
        when(platformAccountMapper.selectList(any())).thenReturn(List.of(
                account(1001L, "TEMU", "ACTIVE"),
                account(1002L, "TEMU", "ACTIVE")));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-7", "{}", null, sign("{}")));
        assertTrue(ex.getMessage().contains("有多个 ACTIVE 店铺账号"), ex.getMessage());
        // 错误信息必须给出真实结论。此前两处文案有问题：
        //  - 「请回调带 shopId」——端点根本没这个参数（控制器恒传 null），照做也无效；
        //  - 「只保留一个 ACTIVE 账号」——多店铺共用同一平台是 ERP 的正常形态，
        //    合成数据里 SHOPIFY 就属于 4 个店铺，把它当错误处理是误导运维。
        assertTrue(ex.getMessage().contains("多店铺共用同一平台是正常形态"), ex.getMessage());
        // 一条都不能落库：猜中概率 1/N，且猜错了没有任何信号
        verify(webhookEventMapper, never()).insert(any(WebhookEvent.class));
    }

    @Test
    @DisplayName("同平台唯一 ACTIVE 店铺时，反查正常命中并落库")
    void uniqueActiveAccountResolves() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);
        when(platformAccountMapper.selectList(any())).thenReturn(List.of(
                account(2002L, "TEMU", "ACTIVE")));

        WebhookEvent saved = service.receiveWebhook(
                "TEMU", "ORDER_CREATED", "EV-8", "{}", null, sign("{}"));

        assertNotNull(saved);
        assertEquals(2002L, saved.getShopId(), "唯一 ACTIVE 账号时反查应命中该店铺");
        verify(webhookEventMapper).insert(any(WebhookEvent.class));
    }

    /**
     * 反查必须只认 ACTIVE 账号。
     * <p>
     * 不能只看「返回一行就命中」——mock 无论代码查不查 status 都会返回同一行，
     * 那样这条测试是假的守门人。所以直接检查拼出来的查询条件里
     * 有没有 status 约束，并断言参数表里带 ACTIVE。
     */
    @Test
    @DisplayName("反查只认 ACTIVE 账号：查询条件必须带 status 约束")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reverseLookupFiltersToActiveAccounts() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);
        when(platformAccountMapper.selectList(any())).thenReturn(List.of(
                account(3003L, "TEMU", "ACTIVE")));

        service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-9", "{}", null, sign("{}"));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(platformAccountMapper).selectList(captor.capture());
        // 先触发 getCustomSqlSegment()：MyBatis-Plus 参数表惰性填充
        String segment = captor.getValue().getCustomSqlSegment();
        assertTrue(segment.toLowerCase().contains("status"),
                "反查必须按 status 过滤，否则 INACTIVE/ERROR 的店也会接走回调：" + segment);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("ACTIVE"),
                "status 条件必须是 ACTIVE，实际参数表=" + captor.getValue().getParamNameValuePairs());
    }

    /**
     * 从 {@code MultiplatformPagingContractTest} 随拆类迁入（§S33.90），断言一字未改。
     * 分页契约本就属于被分页的那个域：webhook 列表搬过来，验证逻辑才跟实现同处一地，
     * 不会出现「实现在 A、契约在 B」的漂移。
     */
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

    // ==================== webhook 分页夹具（随测试迁入） ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<?> captured(Object mapper) {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify((com.baomidou.mybatisplus.core.mapper.BaseMapper<?>) mapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static String sqlOf(LambdaQueryWrapper<?> wrapper) {
        return wrapper.getCustomSqlSegment();
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

    private static PlatformAccount account(Long shopId, String platform, String status) {
        PlatformAccount acc = new PlatformAccount();
        acc.setShopId(shopId);
        acc.setPlatform(platform);
        acc.setStatus(status);
        return acc;
    }
}
