package com.amz.profit;

import com.amz.exception.InvalidParamException;
import com.amz.mapper.CostAllocationMapper;
import com.amz.model.CostAllocation;
import com.amz.service.impl.RealtimeProfitServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成本分摊入账测试。
 * <p>
 * 这一步是「物流/采购头程成本 → 实时利润」的入口：实时快照的 headhaulCost 只读
 * amz_cost_allocation，此前只能靠人在界面上手打一个总额并均摊，既没有来源标识
 * （重复点一次就多算一次），也不能表达采购域已经按数量摊好的实际金额。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("成本分摊入账（来源幂等 + 显式金额）")
class RealtimeProfitAllocationTest {

    @Mock
    private CostAllocationMapper costAllocationMapper;

    @InjectMocks
    private RealtimeProfitServiceImpl service;

    @BeforeEach
    void injectObjectMapper() throws Exception {
        // 实现里 objectMapper 是内部 new 的字段；测试注入同一个 Jackson 实例避免依赖注入顺序
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    @DisplayName("只写 SKU：均摊且尾差归最后一行，合计严格等于总额")
    void evenSplitKeepsRemainderOnLastSku() {
        Map<String, BigDecimal> out = service.allocateCost(1L, "HEADHAUL", new BigDecimal("100.00"),
                Arrays.asList("SKU-A", "SKU-B", "SKU-C"), null, "USD");

        assertEquals(new BigDecimal("33.3333"), out.get("SKU-A"));
        assertEquals(new BigDecimal("33.3333"), out.get("SKU-B"));
        // 尾差落在最后一行：33.3434 才是让合计严格等于 100 的那个数
        assertEquals(new BigDecimal("33.3334"), out.get("SKU-C"));
        assertEquals(0, new BigDecimal("100.00").compareTo(
                out.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        ArgumentCaptor<CostAllocation> captor = ArgumentCaptor.forClass(CostAllocation.class);
        verify(costAllocationMapper).insert(captor.capture());
        assertEquals("EVEN", captor.getValue().getAllocMethod());
        assertEquals("USD", captor.getValue().getCurrency());
    }

    @Test
    @DisplayName("写 SKU:金额：按给定金额入账并标 EXPLICIT，不再重新均摊")
    void explicitAmountsAreBookedAsGiven() {
        Map<String, BigDecimal> out = service.allocateCost(1L, "HEADHAUL", new BigDecimal("900.00"),
                Arrays.asList("SKU-A:600", "SKU-B:300"), "FBA-SHIP-1", "CNY");

        assertEquals(new BigDecimal("600"), out.get("SKU-A"));
        assertEquals(new BigDecimal("300"), out.get("SKU-B"));
        ArgumentCaptor<CostAllocation> captor = ArgumentCaptor.forClass(CostAllocation.class);
        verify(costAllocationMapper).insert(captor.capture());
        CostAllocation row = captor.getValue();
        assertEquals("EXPLICIT", row.getAllocMethod());
        assertEquals("FBA-SHIP-1", row.getSourceRef());
        assertEquals("CNY", row.getCurrency());
        assertTrue(row.getAllocDetails().contains("SKU-A"), row.getAllocDetails());
    }

    @Test
    @DisplayName("给定金额之和与总额不一致：拒绝入账，不静默补差")
    void mismatchedExplicitSumIsRejected() {
        InvalidParamException ex = assertThrows(InvalidParamException.class, () ->
                service.allocateCost(1L, "HEADHAUL", new BigDecimal("900.00"),
                        Arrays.asList("SKU-A:600", "SKU-B:200"), "FBA-SHIP-2", "CNY"));
        assertTrue(ex.getMessage().contains("拒绝入账"), ex.getMessage());
        verify(costAllocationMapper, never()).insert(any(CostAllocation.class));
    }

    @Test
    @DisplayName("混用带金额与不带金额：拒绝，不能把没带金额的那行静默丢掉")
    void mixedEntryStylesRejected() {
        // 总额故意等于带金额那行的 60：若不做混用校验，代码会走「显式金额」分支，
        // 合计正好对得上、静默入账，SKU-B 直接从天上消失——所以这里必须靠混用规则本身挡住
        assertThrows(InvalidParamException.class, () -> service.allocateCost(1L, "HEADHAUL",
                new BigDecimal("60.00"), Arrays.asList("SKU-A:60", "SKU-B"), null, "USD"));
        verify(costAllocationMapper, never()).insert(any(CostAllocation.class));
    }

    @Test
    @DisplayName("同一 sourceRef 第二次入账：不再插入，并返回账上已有明细")
    void sameSourceRefIsIdempotent() {
        CostAllocation stored = new CostAllocation();
        stored.setShopId(1L);
        stored.setCostType("HEADHAUL");
        stored.setSourceRef("FBA-SHIP-3");
        stored.setAllocDetails("{\"SKU-A\":500,\"SKU-B\":400}");
        when(costAllocationMapper.countBySource(1L, "HEADHAUL", "FBA-SHIP-3")).thenReturn(1);
        when(costAllocationMapper.selectBySource(1L, "HEADHAUL", "FBA-SHIP-3")).thenReturn(stored);

        Map<String, BigDecimal> out = service.allocateCost(1L, "HEADHAUL", new BigDecimal("900.00"),
                Arrays.asList("SKU-A:600", "SKU-B:300"), "FBA-SHIP-3", "CNY");

        verify(costAllocationMapper, never()).insert(any(CostAllocation.class));
        // 返回的是账上真值，而不是本次算出来的数
        assertEquals(new BigDecimal("500"), out.get("SKU-A"));
        assertEquals(new BigDecimal("400"), out.get("SKU-B"));
    }

    @Test
    @DisplayName("来源已存在但明细解析不了：退回本次计算值，不返回空 map 假装没摊")
    void unparsableStoredDetailsFallBackToComputed() {
        CostAllocation stored = new CostAllocation();
        stored.setAllocDetails("坏了的 JSON");
        when(costAllocationMapper.countBySource(eq(1L), anyString(), eq("FBA-SHIP-4"))).thenReturn(1);
        when(costAllocationMapper.selectBySource(eq(1L), anyString(), eq("FBA-SHIP-4"))).thenReturn(stored);

        Map<String, BigDecimal> out = service.allocateCost(1L, "HEADHAUL", new BigDecimal("10.00"),
                Collections.singletonList("SKU-A:10"), "FBA-SHIP-4", "USD");

        assertEquals(new BigDecimal("10"), out.get("SKU-A"));
        verify(costAllocationMapper, never()).insert(any(CostAllocation.class));
    }

    @Test
    @DisplayName("非法入参：总额非正、costType 空、SKU 重复都要挡下来")
    void invalidArguments() {
        assertThrows(InvalidParamException.class, () -> service.allocateCost(1L, "HEADHAUL",
                BigDecimal.ZERO, List.of("SKU-A"), null, null));
        assertThrows(InvalidParamException.class, () -> service.allocateCost(1L, " ",
                new BigDecimal("10"), List.of("SKU-A"), null, null));
        assertThrows(InvalidParamException.class, () -> service.allocateCost(1L, "HEADHAUL",
                new BigDecimal("10"), Arrays.asList("SKU-A", "SKU-A"), null, null));
        assertThrows(InvalidParamException.class, () -> service.allocateCost(1L, "HEADHAUL",
                new BigDecimal("10"), Arrays.asList("SKU-A:not-a-number"), null, null));
        assertEquals(Collections.emptyMap(), service.allocateCost(1L, "HEADHAUL",
                new BigDecimal("10"), Collections.emptyList(), null, null));
        verify(costAllocationMapper, never()).insert(any(CostAllocation.class));
    }

    @Test
    @DisplayName("shopId 为空直接拒绝，不落一条无主的分摊")
    void nullShopIsRejected() {
        assertThrows(InvalidParamException.class, () -> service.allocateCost(null, "HEADHAUL",
                new BigDecimal("10.00"), List.of("SKU-A:10"), "FBA-SHIP-5", "USD"));
        verify(costAllocationMapper, never()).insert(any(CostAllocation.class));
    }
}
