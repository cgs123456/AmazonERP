package com.amz.service.impl;

import com.amz.dto.PlatformOrderStats;
import com.amz.mapper.UnifiedOrderMapper;
import com.amz.model.UnifiedOrder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 分平台统计契约（A8）。
 *
 * <p>锁三条：
 * <ol>
 *   <li><b>不产跨平台合计</b>——只给按平台分组的桶，没有「全店销售额」；</li>
 *   <li><b>金额按币种分组</b>——同平台多币种不能混加；</li>
 *   <li><b>触顶要披露</b>——truncated 与 truncatedPlatforms 必须如实标记。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("多平台统计：分平台口径、币种分组、截断披露")
class PlatformOrderStatsServiceTest {

    @Mock
    private UnifiedOrderMapper unifiedOrderMapper;

    private PlatformOrderStatsService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UnifiedOrder.class);
    }

    @BeforeEach
    void setUp() {
        service = new PlatformOrderStatsService();
        ReflectionTestUtils.setField(service, "unifiedOrderMapper", unifiedOrderMapper);
    }

    private static UnifiedOrder order(String platform, String currency, String amount, String status) {
        UnifiedOrder o = new UnifiedOrder();
        o.setShopId(1L);
        o.setPlatform(platform);
        o.setCurrency(currency);
        o.setOriginalAmount(new BigDecimal(amount));
        o.setStatus(status);
        return o;
    }

    @Test
    @DisplayName("按平台分组统计：两个平台各一份，且没有跨平台合计字段被填上")
    void bucketsPerPlatformWithoutGrandTotal() {
        // discoverPlatforms 走 DISTINCT，之后按平台各查一次
        when(unifiedOrderMapper.selectDistinctPlatforms(1L)).thenReturn(List.of("SHEIN", "TEMU"));
        when(unifiedOrderMapper.selectList(any())).thenReturn(
                List.of(order("SHEIN", "USD", "20.00", "PAID")),
                List.of(order("TEMU", "USD", "10.00", "PAID")));

        PlatformOrderStats stats = service.stats(1L, null);

        assertEquals(2, stats.getPlatforms().size());
        assertEquals(2, stats.getTotalOrders());
        for (PlatformOrderStats.PlatformBucket b : stats.getPlatforms()) {
            assertNotNull(b.getPlatform());
            assertEquals(1, b.getOrderCount());
        }
        assertTrue(stats.getTruncatedPlatforms().isEmpty());
    }

    @Test
    @DisplayName("同一平台多币种时金额按币种分组，不合并成一个数")
    void amountGroupedByCurrency() {
        when(unifiedOrderMapper.selectList(any())).thenReturn(
                List.of(order("TEMU", "USD", "10.00", "PAID"), order("TEMU", "EUR", "5.00", "PAID")),
                List.of(order("TEMU", "USD", "10.00", "PAID"), order("TEMU", "EUR", "5.00", "PAID")));

        PlatformOrderStats stats = service.stats(1L, List.of("TEMU"));

        PlatformOrderStats.PlatformBucket bucket = stats.getPlatforms().get(0);
        assertEquals(2, bucket.getOrderCount());
        assertEquals(new BigDecimal("10.00"), bucket.getAmountByCurrency().get("USD"));
        assertEquals(new BigDecimal("5.00"), bucket.getAmountByCurrency().get("EUR"));
        // 刻意没有「15.00」这种跨币种合计
        assertFalse(bucket.getAmountByCurrency().containsKey("TOTAL"));
    }

    @Test
    @DisplayName("状态分布按状态分组计数")
    void statusCounts() {
        when(unifiedOrderMapper.selectList(any())).thenReturn(
                List.of(order("TIKTOK", "USD", "10.00", "PAID"), order("TIKTOK", "USD", "20.00", "SHIPPED")),
                List.of(order("TIKTOK", "USD", "10.00", "PAID"), order("TIKTOK", "USD", "20.00", "SHIPPED")));

        PlatformOrderStats stats = service.stats(1L, List.of("TIKTOK"));

        PlatformOrderStats.PlatformBucket bucket = stats.getPlatforms().get(0);
        assertEquals(1, bucket.getStatusCounts().get("PAID"));
        assertEquals(1, bucket.getStatusCounts().get("SHIPPED"));
        assertEquals(new BigDecimal("30.00"), bucket.getAmountByCurrency().get("USD"));
    }

    @Test
    @DisplayName("行数触顶时标记 truncated 并点名平台，不静默截断")
    void truncationIsDisclosed() {
        List<UnifiedOrder> many = new java.util.ArrayList<>();
        // 上限 5000，多取一行即判定触顶
        for (int i = 0; i <= PlatformOrderStatsService.MAX_ROWS_PER_PLATFORM; i++) {
            many.add(order("TEMU", "USD", "1.00", "PAID"));
        }
        when(unifiedOrderMapper.selectList(any())).thenReturn(many);

        PlatformOrderStats stats = service.stats(1L, List.of("TEMU"));

        PlatformOrderStats.PlatformBucket bucket = stats.getPlatforms().get(0);
        assertTrue(bucket.isTruncated());
        // 统计只算上限内的行，多取的那行不进统计
        assertEquals(PlatformOrderStatsService.MAX_ROWS_PER_PLATFORM, bucket.getOrderCount());
        assertTrue(stats.getTruncatedPlatforms().contains("TEMU"));
    }

    @Test
    @DisplayName("shopId 为空直接拒绝")
    void nullShopIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.stats(null, null));
    }

    /**
     * §S33.87：平台发现不能受「统计行数上限」约束。
     * <p>
     * 旧实现取 {@code LIMIT 5001} 行再内存去重。店铺订单超过上限时，
     * <b>只出现在 5001 行之后的平台会被整个漏掉</b>，而且不会标 truncated——
     * 统计结果看起来完整，实际少了一整个平台的订单。
     * 这是「有界读」被误用到「枚举维度」上的典型错误：行数上限该约束
     * 「统计多少行」，不该约束「有哪些平台」。
     */
    @Test
    @DisplayName("平台只出现在统计上限之后时仍必须被发现（DISTINCT 不受行数上限约束）")
    void discoversPlatformsBeyondRowLimit() {
        // 假设店铺 TEMU 有 6000 单、SHEIN 有 10 单，且 SHEIN 排在 5001 行之后。
        // DISTINCT 查询与行数无关，两个平台都必须出现。
        when(unifiedOrderMapper.selectDistinctPlatforms(1L)).thenReturn(List.of("SHEIN", "TEMU"));
        when(unifiedOrderMapper.selectList(any())).thenReturn(
                List.of(order("SHEIN", "USD", "20.00", "PAID")),
                List.of(order("TEMU", "USD", "10.00", "PAID")));

        PlatformOrderStats stats = service.stats(1L, null);

        List<String> found = stats.getPlatforms().stream()
                .map(PlatformOrderStats.PlatformBucket::getPlatform)
                .sorted()
                .toList();
        assertEquals(List.of("SHEIN", "TEMU"), found,
                "平台发现必须与订单行数无关；漏掉一个平台等于整块业务数据消失且不报警");
    }

    @Test
    @DisplayName("平台名首尾空格不会把同一平台拆成两个桶")
    void trimsPlatformNamesFromDirtyData() {
        when(unifiedOrderMapper.selectDistinctPlatforms(1L))
                .thenReturn(List.of(" TEMU ", "TEMU"));
        when(unifiedOrderMapper.selectList(any())).thenReturn(List.of());

        PlatformOrderStats stats = service.stats(1L, null);

        assertEquals(1, stats.getPlatforms().size(),
                "历史脏数据里的首尾空格不能让同一平台出现两次；实际=" + stats.getPlatforms().size());
        assertEquals("TEMU", stats.getPlatforms().get(0).getPlatform());
    }
}
