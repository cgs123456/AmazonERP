package com.amz.service.impl;

import com.amz.client.KingdeeClient;
import com.amz.context.UserContext;
import com.amz.dto.KingdeeSyncResult;
import com.amz.exception.ConnectorException;
import com.amz.finance.CurrencyConverter;
import com.amz.mapper.AccountingVoucherMapper;
import com.amz.model.AccountingVoucher;
import com.amz.exception.InvalidParamException;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import com.amz.service.VatService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业财一体化服务实现单元测试（纯 Mockito，不依赖 Spring 容器）。
 * <p>
 * 覆盖：generateOrderVoucher（含币种换算）、calculateProfit（含 4 种 sourceType
 * 按借贷方向加减）、syncToKingdee（mock/真实客户端、伪单号、异常、凭证不存在）、listVouchers。
 */
@ExtendWith(MockitoExtension.class)
class FinanceServiceImplTest {

    @Mock
    private AccountingVoucherMapper voucherMapper;

    @Mock
    private CurrencyConverter currencyConverter;

    @Mock
    private KingdeeClient kingdeeClient;

    @Mock
    private VatService vatService;

    @InjectMocks
    private FinanceServiceImpl financeService;

    /**
     * 纯 Mockito 测试里没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时
     * 会抛 “can not find lambda cache for this entity”。这里手动注册表元信息，
     * 分页断言才能检查真实拼出来的条件（而不是只看有没有调用 mapper）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AccountingVoucher.class);
    }
    // ---------------- generateOrderVoucher ----------------

    @Test
    @DisplayName("generateOrderVoucher：USD 订单触发币种换算并落库，凭证号 UUID 防并发冲突")
    void generateOrderVoucherUsdWithConversion() {
        Long shopId = 10L;
        String orderNo = "AMZ-ORD-001";
        BigDecimal amount = new BigDecimal("100");
        when(currencyConverter.convertToCny(amount, "USD")).thenReturn(new BigDecimal("725.00"));
        when(currencyConverter.getRate("USD")).thenReturn(new BigDecimal("7.25"));

        AccountingVoucher result = financeService.generateOrderVoucher(shopId, orderNo, amount, "USD");

        ArgumentCaptor<AccountingVoucher> captor = ArgumentCaptor.forClass(AccountingVoucher.class);
        verify(voucherMapper).insert(captor.capture());
        AccountingVoucher persisted = captor.getValue();

        // 凭证号：V + 32 位 hex（UUID 去横线），规避时间戳并发冲突
        assertNotNull(persisted.getVoucherNo());
        assertTrue(persisted.getVoucherNo().matches("V[0-9a-f]{32}"),
                "凭证号应为 V+UUID去横线，实际: " + persisted.getVoucherNo());
        assertEquals(shopId, persisted.getShopId());
        assertEquals(orderNo, persisted.getSourceNo());
        assertEquals("ORDER", persisted.getSourceType());
        assertEquals("PENDING", persisted.getKingdeeSyncStatus());
        assertEquals("USD", persisted.getCurrency());
        assertEquals(new BigDecimal("7.25"), persisted.getExchangeRate());
        assertEquals(new BigDecimal("725.00"), persisted.getCnyAmount());
        assertEquals(amount, persisted.getOriginalAmount());
        // 返回值即落库对象
        assertEquals(persisted.getVoucherNo(), result.getVoucherNo());
    }

    @Test
    @DisplayName("generateOrderVoucher：CNY 订单无换算，原值即本位币")
    void generateOrderVoucherCnyNoConversion() {
        when(currencyConverter.convertToCny(new BigDecimal("200"), "CNY")).thenReturn(new BigDecimal("200.00"));
        when(currencyConverter.getRate("CNY")).thenReturn(BigDecimal.ONE);

        AccountingVoucher v = financeService.generateOrderVoucher(5L, "AMZ-ORD-002", new BigDecimal("200"), "CNY");

        verify(voucherMapper).insert(any(AccountingVoucher.class));
        assertEquals("CNY", v.getCurrency());
        assertEquals(new BigDecimal("200.00"), v.getCnyAmount());
        assertEquals("ORDER", v.getSourceType());
    }

    // ---------------- calculateProfit ----------------

    @Test
    @DisplayName("calculateProfit：ORDER(+) - PROCUREMENT - PLATFORM_FEE - REFUND，未知类型忽略，null 金额按 0")
    void calculateProfitFourSourceTypesByDebitCredit() {
        when(voucherMapper.sumBySourceType(eq(1L), eq("2026-01-01"), eq("2026-01-31"))).thenReturn(
                Arrays.asList(
                        group("ORDER", null, new BigDecimal("1000"), new BigDecimal("1000"), null),
                        group("PROCUREMENT", null, new BigDecimal("300"), null, null),
                        group("PLATFORM_FEE", null, new BigDecimal("50"), null, null),
                        group("REFUND", null, new BigDecimal("80"), null, null),
                        group("UNKNOWN", null, new BigDecimal("999"), null, null),
                        group("ORDER", null, null, null, null))); // null 金额按 0

        // 利润 = 1000 - 300 - 50 - 80 + 0 = 570.00（VAT 被 mock 为 null，跳过）
        BigDecimal profit = financeService.calculateProfit(1L, "2026-01-01", "2026-01-31");

        assertEquals(new BigDecimal("570.00"), profit);
    }

    @Test
    @DisplayName("calculateProfit：无凭证时返回 0.00")
    void calculateProfitEmptyReturnsZero() {
        when(voucherMapper.sumBySourceType(eq(1L), isNull(), isNull()))
                .thenReturn(Collections.emptyList());
        BigDecimal profit = financeService.calculateProfit(1L, null, null);
        assertEquals(new BigDecimal("0.00"), profit);
    }

    @Test
    @DisplayName("calculateProfit：纯订单收入场景（仅 ORDER）")
    void calculateProfitOnlyOrder() {
        when(voucherMapper.sumBySourceType(eq(1L), isNull(), isNull())).thenReturn(Arrays.asList(
                group("ORDER", null, new BigDecimal("500"), new BigDecimal("500"), null),
                group("ORDER", null, new BigDecimal("250.50"), new BigDecimal("250.50"), null)));
        BigDecimal profit = financeService.calculateProfit(1L, null, null);
        assertEquals(new BigDecimal("750.50"), profit);
    }

    @Test
    @DisplayName("calculateProfit：结算币种映射国家计 VAT（USD 零税率，GBP 按 UK20%，EUR 保持默认 20%）")
    void calculateProfitVatByCurrencyCountry() {
        // 用真实 VatServiceImpl（零税率国家 US/CN/JP 已显式入库），验证币种→国家映射
        FinanceServiceImpl service = new FinanceServiceImpl();
        ReflectionTestUtils.setField(service, "voucherMapper", voucherMapper);
        ReflectionTestUtils.setField(service, "vatService", new com.amz.service.impl.VatServiceImpl());

        when(voucherMapper.sumBySourceType(eq(1L), isNull(), isNull())).thenReturn(Arrays.asList(
                group("ORDER", "USD", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("7.25")),
                group("ORDER", "GBP", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("9.20")),
                group("ORDER", "EUR", new BigDecimal("100"), new BigDecimal("100"), null)));

        // VAT 按原币算出后必须折 CNY 再扣：300 - 0(USD) - 20×9.2(GBP) - 20×1(EUR无汇率按1) = 96.00
        // （单位错配时为 260.00，利润虚高 164）
        assertEquals(new BigDecimal("96.00"), service.calculateProfit(1L, null, null));
    }

    // ---------------- syncToKingdee ----------------

    @Test
    @DisplayName("syncToKingdee：真实客户端返回 KD 号 → 状态 SYNCED，返回 true")
    void syncToKingdeeRealClientReturnsSynced() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(1L);
        v.setVoucherNo("Vabc");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(1L)).thenReturn(v);
        // 原子认领成功（PENDING/FAILED → SYNCING 影响 1 行）
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.syncVoucher(any())).thenReturn("KD-1700000000000");

        KingdeeSyncResult result = financeService.syncToKingdee(1L);

        assertEquals(KingdeeSyncResult.Status.SYNCED, result.status());
        assertTrue(result.isRealSuccess());
        assertEquals("SYNCED", v.getKingdeeSyncStatus());
        verify(voucherMapper).updateById(v);
    }

    @Test
    @DisplayName("syncToKingdee：客户端返回 KINGDEE_MOCK_ 伪单号 → 状态 FAILED，返回 false")
    void syncToKingdeeRejectsPlaceholderNumber() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(2L);
        v.setVoucherNo("Vdef");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(2L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.syncVoucher(any())).thenReturn("KINGDEE_MOCK_1700000000000");

        KingdeeSyncResult result = financeService.syncToKingdee(2L);

        assertEquals(KingdeeSyncResult.Status.FAILED, result.status());
        assertFalse(result.isRealSuccess());
        assertEquals("FAILED", v.getKingdeeSyncStatus(),
                "占位号绝不能作为同步成功证据");
        verify(voucherMapper).updateById(v);
    }

    @Test
    @DisplayName("syncToKingdee：客户端返回空凭证号 → 状态 FAILED，返回 false")
    void syncToKingdeeRejectsBlankNumber() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(5L);
        v.setVoucherNo("Vblank");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(5L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.syncVoucher(any())).thenReturn("  ");

        KingdeeSyncResult result = financeService.syncToKingdee(5L);

        assertEquals(KingdeeSyncResult.Status.FAILED, result.status());
        assertFalse(result.isRealSuccess());
        assertEquals("FAILED", v.getKingdeeSyncStatus());
        verify(voucherMapper).updateById(v);
    }

    @Test
    @DisplayName("syncToKingdee：客户端抛异常 → 状态 FAILED，返回 false")
    void syncToKingdeeClientThrowsMarksFailed() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(3L);
        v.setVoucherNo("Vghi");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(3L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.syncVoucher(any())).thenThrow(new RuntimeException("金蝶网关超时"));

        KingdeeSyncResult result = financeService.syncToKingdee(3L);

        assertEquals(KingdeeSyncResult.Status.FAILED, result.status());
        assertFalse(result.isRealSuccess());
        assertEquals("FAILED", v.getKingdeeSyncStatus());
        verify(voucherMapper).updateById(v);
    }

    @Test
    @DisplayName("syncToKingdee：mock 客户端成功时标记 MOCK，不冒充真实 SYNCED")
    void syncToKingdeeMockClientUsesExplicitMockStatus() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(6L);
        v.setVoucherNo("Vmock");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(6L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.isMock()).thenReturn(true);
        when(kingdeeClient.syncVoucher(any())).thenReturn("KINGDEE_MOCK_1700000000000");

        KingdeeSyncResult result = financeService.syncToKingdee(6L);

        assertEquals(KingdeeSyncResult.Status.MOCK, result.status());
        assertFalse(result.isRealSuccess(), "MOCK 不能冒充真实 SYNCED");
        assertEquals("MOCK", v.getKingdeeSyncStatus());
        verify(voucherMapper).updateById(v);
    }

    @Test
    @DisplayName("syncToKingdee：切到真实客户端后可重新认领 MOCK 并补真实入账")
    void syncToKingdeeRealClientReclaimsMockStatus() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(13L);
        v.setVoucherNo("Vmock-reclaim");
        v.setKingdeeSyncStatus("MOCK");
        when(voucherMapper.selectById(13L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.isMock()).thenReturn(false);
        when(kingdeeClient.syncVoucher(any())).thenReturn("KD-1700000000003");

        KingdeeSyncResult result = financeService.syncToKingdee(13L);

        assertEquals(KingdeeSyncResult.Status.SYNCED, result.status());
        assertTrue(result.isRealSuccess(), "真实客户端补同步成功必须转为 SYNCED");
        assertEquals("SYNCED", v.getKingdeeSyncStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<AccountingVoucher>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(voucherMapper).update(isNull(), captor.capture());
        String claimSql = captor.getValue().getSqlSegment();
        assertTrue(claimSql.contains("MOCK") || captor.getValue().getParamNameValuePairs().containsValue("MOCK"),
                "真实客户端启用后，MOCK 必须进入可重新认领状态，实际 SQL: " + claimSql
                        + " 参数: " + captor.getValue().getParamNameValuePairs());
    }
    @Test
    @DisplayName("syncToKingdee：超时 SYNCING 可被重新认领，避免进程崩溃后永久卡死")
    void syncToKingdeeReclaimsStaleSyncing() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(7L);
        v.setVoucherNo("Vstale");
        v.setKingdeeSyncStatus("SYNCING");
        v.setUpdateTime(LocalDateTime.now().minusMinutes(10));
        when(voucherMapper.selectById(7L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.syncVoucher(any())).thenReturn("KD-1700000000001");

        KingdeeSyncResult result = financeService.syncToKingdee(7L);

        assertEquals(KingdeeSyncResult.Status.SYNCED, result.status());
        assertTrue(result.isRealSuccess());
        assertEquals("SYNCED", v.getKingdeeSyncStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<AccountingVoucher>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(voucherMapper).update(isNull(), captor.capture());
        UpdateWrapper<AccountingVoucher> claim = captor.getValue();
        assertTrue(claim.getSqlSegment().contains("update_time"),
                "认领条件必须包含租约时间，实际 SQL: " + claim.getSqlSegment());
        assertTrue(claim.getParamNameValuePairs().containsValue("SYNCING"),
                "认领条件必须允许超时 SYNCING，实际参数: " + claim.getParamNameValuePairs());
    }

    @Test
    @DisplayName("syncToKingdee：未超时的 SYNCING 不可重复认领，不重复调用金蝶")
    void syncToKingdeeFreshSyncingIsNotReclaimed() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(8L);
        v.setVoucherNo("Vfresh");
        v.setKingdeeSyncStatus("SYNCING");
        v.setUpdateTime(LocalDateTime.now());
        when(voucherMapper.selectById(8L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(0);

        KingdeeSyncResult result = financeService.syncToKingdee(8L);

        assertEquals(KingdeeSyncResult.Status.SKIPPED, result.status());
        assertFalse(result.isRealSuccess(), "并发中的同步不能报告为真实成功");
        verify(kingdeeClient, never()).syncVoucher(any());
        verify(voucherMapper, never()).updateById(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("syncToKingdee：认领失败（他人正在同步/已同步）→ 跳过且不调用金蝶客户端")
    void syncToKingdeeClaimFailsSkipsRemoteCall() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(4L);
        v.setVoucherNo("Vjkl");
        v.setKingdeeSyncStatus("SYNCING");
        when(voucherMapper.selectById(4L)).thenReturn(v);
        // 原子认领影响 0 行：状态已非 PENDING/FAILED
        when(voucherMapper.update(isNull(), any())).thenReturn(0);

        KingdeeSyncResult result = financeService.syncToKingdee(4L);

        assertEquals(KingdeeSyncResult.Status.SKIPPED, result.status());
        assertFalse(result.isRealSuccess(), "认领失败的跳过不能冒充真实成功");
        verify(kingdeeClient, never()).syncVoucher(any());
        verify(voucherMapper, never()).updateById(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("syncToKingdee：凭证不存在 → 返回 false，不调用 updateById")
    void syncToKingdeeVoucherNotFound() {
        when(voucherMapper.selectById(99L)).thenReturn(null);

        KingdeeSyncResult result = financeService.syncToKingdee(99L);

        assertEquals(KingdeeSyncResult.Status.NOT_FOUND, result.status());
        assertFalse(result.isRealSuccess());
        verify(voucherMapper, never()).updateById(any(AccountingVoucher.class));
    }

    @Test
    @DisplayName("syncToKingdee：SYNCING 且 update_time 为空时也视为租约失效，必须允许重新认领")
    void syncToKingdeeReclaimsSyncingWithNullUpdateTime() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(10L);
        v.setVoucherNo("Vnull-time");
        v.setKingdeeSyncStatus("SYNCING");
        v.setUpdateTime(null);
        when(voucherMapper.selectById(10L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.syncVoucher(any())).thenReturn("KD-1700000000002");

        KingdeeSyncResult result = financeService.syncToKingdee(10L);

        assertEquals(KingdeeSyncResult.Status.SYNCED, result.status());
        assertTrue(result.isRealSuccess());
        assertEquals("SYNCED", v.getKingdeeSyncStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<AccountingVoucher>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(voucherMapper).update(isNull(), captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        assertTrue(sql.contains("update_time is null"),
                "update_time 为 NULL 的 SYNCING 也必须能重新认领，实际 SQL: " + sql);
    }

    @Test
    @DisplayName("syncToKingdee：未授权店铺 → FORBIDDEN，且不调用金蝶")
    void syncToKingdeeForbiddenShop() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(11L);
        v.setShopId(100L);
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(11L)).thenReturn(v);
        UserContext.setUserId(1);
        UserContext.setShops(List.of(200L));
        try {
            KingdeeSyncResult result = financeService.syncToKingdee(11L);

            assertEquals(KingdeeSyncResult.Status.FORBIDDEN, result.status());
            assertFalse(result.isRealSuccess());
            verify(kingdeeClient, never()).syncVoucher(any());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    @DisplayName("syncToKingdee：连接器未配置 → NOT_CONFIGURED，状态仍保留 FAILED 供重试")
    void syncToKingdeeNotConfigured() {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(12L);
        v.setVoucherNo("V-not-configured");
        v.setKingdeeSyncStatus("PENDING");
        when(voucherMapper.selectById(12L)).thenReturn(v);
        when(voucherMapper.update(isNull(), any())).thenReturn(1);
        when(kingdeeClient.syncVoucher(any())).thenThrow(
                ConnectorException.notConfigured("kingdee", "缺少 kingdee.app-secret"));

        KingdeeSyncResult result = financeService.syncToKingdee(12L);

        assertEquals(KingdeeSyncResult.Status.NOT_CONFIGURED, result.status());
        assertFalse(result.isRealSuccess());
        assertEquals("FAILED", v.getKingdeeSyncStatus());
        verify(voucherMapper).updateById(v);
    }

    // ---------------- listVouchers（游标分页 / 截断显式化） ----------------

    @Test
    @DisplayName("listVouchers：未取满一页时 truncated=false，且没有 nextCursor")
    void listVouchersNoTruncation() {
        when(voucherMapper.selectList(any())).thenReturn(Arrays.asList(voucherWithId(2L), voucherWithId(1L)));

        PageResult<AccountingVoucher> page = financeService.listVouchers(10L, null, PageRequest.first(50));

        assertEquals(2, page.items().size());
        assertFalse(page.truncated());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        assertNull(page.total(), "不做 COUNT(*)，总数必须是「未知」而不是 0");
    }

    @Test
    @DisplayName("listVouchers：命中超过一页时 truncated=true 且给出 nextCursor，探测行不进入结果")
    void listVouchersTruncated() {
        List<AccountingVoucher> probed = new ArrayList<>();
        probed.add(voucherWithId(20L));
        probed.add(voucherWithId(19L));
        probed.add(voucherWithId(18L));
        when(voucherMapper.selectList(any())).thenReturn(probed);

        PageResult<AccountingVoucher> page = financeService.listVouchers(10L, null, PageRequest.first(2));

        assertEquals(2, page.items().size(), "第 size+1 行只用于判定 hasMore，不能返回给调用方");
        assertTrue(page.truncated());
        assertTrue(page.hasMore());
        assertEquals(PageRequest.encodeCursor(19L), page.nextCursor(), "游标必须是本页最后一行的排序键");
        assertEquals(Arrays.asList(20L, 19L), page.items().stream().map(AccountingVoucher::getId).toList());
    }

    @Test
    @DisplayName("listVouchers：携带 cursor 时按下界过滤，且始终带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listVouchersWithCursorUsesKeyset() {
        when(voucherMapper.selectList(any())).thenReturn(List.of(voucherWithId(5L)));
        PageRequest req = PageRequest.of(10, PageRequest.encodeCursor(900L));

        financeService.listVouchers(10L, "ORDER", req);

        ArgumentCaptor<LambdaQueryWrapper<AccountingVoucher>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(voucherMapper).selectList(captor.capture());
        LambdaQueryWrapper<AccountingVoucher> wrapper = captor.getValue();
        // 必须先触发 getCustomSqlSegment()：MyBatis-Plus 的参数表是惰性填充的，
        // 先取 paramNameValuePairs 会拿到空 Map，断言会假失败。
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"),
                "列表查询必须带显式 LIMIT，不允许无界 selectList");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900L),
                "keyset 分页必须把游标值作为 id 的下界，实际参数表=" + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("listVouchers：sourceType 过滤仍然生效")
    void listVouchersWithSourceType() {
        when(voucherMapper.selectList(any())).thenReturn(Collections.emptyList());

        PageResult<AccountingVoucher> page = financeService.listVouchers(10L, "ORDER", PageRequest.first(10));

        assertTrue(page.items().isEmpty());
        verify(voucherMapper).selectList(any());
    }

    @Test
    @DisplayName("listVouchers：page 为 null 时退化为首页默认页大小，不抛异常")
    void listVouchersDefaultsToFirstPage() {
        when(voucherMapper.selectList(any())).thenReturn(Collections.emptyList());

        PageResult<AccountingVoucher> page = financeService.listVouchers(10L, null, null);

        assertTrue(page.items().isEmpty());
        assertEquals(PageRequest.DEFAULT_SIZE, page.size());
    }

    @Test
    @DisplayName("分页参数：size 超过硬上限必须报错，而不是静默收敛到上限")
    void pageRequestRejectsOversizedSize() {
        assertThrows(InvalidParamException.class, () -> PageRequest.of(PageRequest.MAX_SIZE + 1, null));
        assertThrows(InvalidParamException.class, () -> PageRequest.of(0, null));
    }

    @Test
    @DisplayName("分页参数：不可解析的 cursor 必须报错，而不是静默回退到首页")
    void pageRequestRejectsBadCursor() {
        assertThrows(InvalidParamException.class, () -> PageRequest.of(10, "not-a-cursor"));
        assertThrows(InvalidParamException.class, () -> PageRequest.of(10, PageRequest.encodeCursor(-1L)));
    }
    // ---------------- helpers ----------------

    private AccountingVoucher voucherWithId(Long id) {
        AccountingVoucher v = new AccountingVoucher();
        v.setId(id);
        v.setShopId(10L);
        return v;
    }
    private AccountingVoucher voucher(String sourceType, BigDecimal cnyAmount) {
        AccountingVoucher v = new AccountingVoucher();
        v.setShopId(1L);
        v.setBizDate("2026-01-15");
        v.setSourceType(sourceType);
        v.setCnyAmount(cnyAmount);
        return v;
    }

    /**
     * 构造 sumBySourceType 返回的聚合分组 {sourceType, currency, totalCny, totalOriginal, rate}。
     */
    private java.util.Map<String, Object> group(String sourceType, String currency,
                                                BigDecimal totalCny, BigDecimal totalOriginal,
                                                BigDecimal rate) {
        java.util.Map<String, Object> g = new java.util.LinkedHashMap<>();
        g.put("sourceType", sourceType);
        g.put("currency", currency);
        g.put("totalCny", totalCny);
        g.put("totalOriginal", totalOriginal);
        g.put("rate", rate);
        return g;
    }
}
