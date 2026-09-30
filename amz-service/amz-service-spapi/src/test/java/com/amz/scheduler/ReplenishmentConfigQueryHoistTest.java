package com.amz.scheduler;

import com.amz.engine.HybridReplenishmentEngine;
import com.amz.engine.ReplenishmentEngine;
import com.amz.engine.ml.MlDemandPredictor;
import com.amz.mapper.FbaInventoryMapper;
import com.amz.mapper.PromotionCalendarMapper;
import com.amz.mapper.ReplenishmentSuggestionMapper;
import com.amz.mapper.SalesHistoryMapper;
import com.amz.mapper.SeasonalIndexMapper;
import com.amz.model.FbaInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补货调度与引擎的 SQL 放大检查。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 性能审查 High P1）</b>：季节性指数与促销乘数只跟
 * (category, 当天) 有关，同一店铺的所有 SKU 用的是同一个 {@code DEFAULT_CATEGORY}，
 * 旧实现却在每个 SKU 的循环里各查一次这两张<b>小配置表</b>；其中促销日历那条查询
 * 连 category 条件都不带，每次返回完全相同的行。
 * 100 店 × 2000 SKU 就是 40 万次重复扫描，而这些数据一轮根本不会变。
 * <p>
 * 第二个检查点：混合引擎为了算 CV / ML 特征自己查一次 30 天销量历史，
 * {@code super.generateSuggestion} 里又查一次同样的行 —— 每个 SKU 多一条 SQL。
 * <p>
 * 这里用<b>真实引擎 + mock mapper</b>，断言的是 SQL 次数而不是方法调用次数——
 * 只 mock 引擎的话，把查询挪回 SKU 循环里测试照样绿。
 */
@DisplayName("补货调度：配置表每轮查一次，销量历史每 SKU 查一次")
class ReplenishmentConfigQueryHoistTest {

    @Test
    @DisplayName("3 个 SKU 的一轮计算：两张配置表各 1 次 SQL，销量历史仍按 SKU 各 1 次")
    void configTablesAreQueriedOncePerShopRun() {
        SalesHistoryMapper salesHistoryMapper = mock(SalesHistoryMapper.class);
        SeasonalIndexMapper seasonalIndexMapper = mock(SeasonalIndexMapper.class);
        PromotionCalendarMapper promotionCalendarMapper = mock(PromotionCalendarMapper.class);
        when(salesHistoryMapper.selectList(any())).thenReturn(List.of());
        when(seasonalIndexMapper.selectList(any())).thenReturn(List.of());
        when(promotionCalendarMapper.selectList(any())).thenReturn(List.of());

        ReplenishmentEngine engine = new ReplenishmentEngine();
        ReflectionTestUtils.setField(engine, "salesHistoryMapper", salesHistoryMapper);
        ReflectionTestUtils.setField(engine, "seasonalIndexMapper", seasonalIndexMapper);
        ReflectionTestUtils.setField(engine, "promotionCalendarMapper", promotionCalendarMapper);

        assertEquals(3, newScheduler(engine, List.of(inventory("S1"), inventory("S2"), inventory("S3")))
                .calcShopReplenishment(7L), "三个 SKU 都该产出建议");

        verify(seasonalIndexMapper, times(1)).selectList(any());
        verify(promotionCalendarMapper, times(1)).selectList(any());
        verify(salesHistoryMapper, times(3)).selectList(any());
    }

    @Test
    @DisplayName("混合引擎：规则路径复用子类那一次历史查询，父类 mapper 一条 SQL 都不发")
    void hybridEngineSharesOneSalesHistoryQuery() throws Exception {
        SalesHistoryMapper childHistoryMapper = mock(SalesHistoryMapper.class);
        SalesHistoryMapper parentHistoryMapper = mock(SalesHistoryMapper.class);
        SeasonalIndexMapper seasonalIndexMapper = mock(SeasonalIndexMapper.class);
        PromotionCalendarMapper promotionCalendarMapper = mock(PromotionCalendarMapper.class);
        when(childHistoryMapper.selectList(any())).thenReturn(List.of());
        when(parentHistoryMapper.selectList(any())).thenReturn(List.of());
        when(seasonalIndexMapper.selectList(any())).thenReturn(List.of());
        when(promotionCalendarMapper.selectList(any())).thenReturn(List.of());

        HybridReplenishmentEngine engine = new HybridReplenishmentEngine();
        // 父子两份同名 salesHistoryMapper 字段：ReflectionTestUtils 只会命中子类那份，
        // 这里故意注入两个不同 mock，才能证明父类路径没有再查一次。
        inject(ReplenishmentEngine.class, engine, "salesHistoryMapper", parentHistoryMapper);
        inject(ReplenishmentEngine.class, engine, "seasonalIndexMapper", seasonalIndexMapper);
        inject(ReplenishmentEngine.class, engine, "promotionCalendarMapper", promotionCalendarMapper);
        inject(HybridReplenishmentEngine.class, engine, "salesHistoryMapper", childHistoryMapper);

        MlDemandPredictor predictor = mock(MlDemandPredictor.class);
        when(predictor.isModelLoaded()).thenReturn(false);
        ReflectionTestUtils.setField(engine, "predictor", predictor);

        ReplenishmentScheduler scheduler = newScheduler(engine, List.of(inventory("S1")));
        assertEquals(1, scheduler.calcShopReplenishment(7L));

        verify(childHistoryMapper, times(1)).selectList(any());
        verify(parentHistoryMapper, never()).selectList(any());
    }

    private static void inject(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ReplenishmentScheduler newScheduler(ReplenishmentEngine engine, List<FbaInventory> inventories) {
        FbaInventoryMapper inventoryMapper = mock(FbaInventoryMapper.class);
        when(inventoryMapper.selectList(any())).thenReturn(inventories);
        ReplenishmentSuggestionMapper suggestionMapper = mock(ReplenishmentSuggestionMapper.class);
        when(suggestionMapper.selectOne(any())).thenReturn(null);

        ReplenishmentScheduler scheduler = new ReplenishmentScheduler();
        ReflectionTestUtils.setField(scheduler, "replenishmentEngine", engine);
        ReflectionTestUtils.setField(scheduler, "fbaInventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(scheduler, "replenishmentSuggestionMapper", suggestionMapper);
        return scheduler;
    }

    private static FbaInventory inventory(String sku) {
        FbaInventory inv = new FbaInventory();
        inv.setShopId(7L);
        inv.setSku(sku);
        inv.setAsin("A-" + sku);
        inv.setAvailableQuantity(10);
        return inv;
    }
}
