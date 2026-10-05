package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.InventoryAlertMapper;
import com.amz.mapper.WarehouseStockMapper;
import com.amz.model.WarehouseStock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 库龄分析（{@code GET /logistics/warehouse/stock/aging/{shopId}}）的可空列行为。
 * <p>
 * 动因：这条端点在接前端之前从没被浏览器调用过，而它的 Top-10 段用的是
 * {@code Map.of(...)} 与 {@code Comparator.comparingInt(...)} —— 三列在 DDL 里都可空
 * （{@code warehouse_name VARCHAR(128)}、{@code days_in_stock INT DEFAULT 0}、
 * {@code available_qty INT DEFAULT 0}）。{@code Map.of} 不接受 null 值，
 * {@code comparingInt} 与 {@code getAvailableQty() > 0} 会自动拆箱，
 * 所以任意一条快照缺这几列就会让整页 500。分段统计那一段本来就做了 null 兜底，
 * 两处口径必须一致，否则「有分段没明细」这种半屏状态会直接把异常摆到页面上。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("库龄分析：可空快照列不得让端点炸掉")
class WarehouseAgingAnalysisTest {

    @Mock
    private WarehouseStockMapper warehouseStockMapper;

    @Mock
    private InventoryAlertMapper inventoryAlertMapper;

    @InjectMocks
    private MultiWarehouseServiceImpl service;

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(9);
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private static WarehouseStock stock(String sku, Integer qty, Integer days, String warehouseName) {
        WarehouseStock s = new WarehouseStock();
        s.setId(1L);
        s.setShopId(1L);
        s.setWarehouseId(7L);
        s.setSku(sku);
        s.setAvailableQty(qty);
        s.setDaysInStock(days);
        s.setWarehouseName(warehouseName);
        s.setTotalValue(new BigDecimal("10.00"));
        return s;
    }

    @Test
    @DisplayName("三列同时为 null 的快照：不抛异常，且缺失值按 0 / 空串处理")
    void nullColumnsDoNotBlowUpTheAnalysis() {
        when(warehouseStockMapper.selectList(any())).thenReturn(List.of(
                stock("SKU-FULL", 5, 200, "美西仓"),
                stock("SKU-NULL-ALL", null, null, null),
                stock("SKU-NULL-NAME", 3, null, null)));

        Map<String, Object> result = assertDoesNotThrow(() -> service.agingAnalysis(1L));

        assertNotNull(result.get("aging"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) result.get("oldestTop10");
        // 明细只收「有可用库存」的行：available_qty 为 null 的那条既不参与分段也不进明细
        assertEquals(2, top.size(), "两行有可用库存，null 天数的行仍要出现（按 0 天）：" + top);
        assertEquals("SKU-FULL", top.get(0).get("sku"), "按天数倒序，200 天在前");
        assertEquals("SKU-NULL-NAME", top.get(1).get("sku"));

        @SuppressWarnings("unchecked")
        Map<String, Object> aging = (Map<String, Object>) result.get("aging");
        @SuppressWarnings("unchecked")
        Map<String, Object> fresh = (Map<String, Object>) aging.get("fresh_30d");
        // days_in_stock 为 null 的行按 0 天算 → 落进 ≤30 桶（与分段循环的兜底口径一致）
        assertEquals(1L, ((Number) fresh.get("count")).longValue(),
                "null 天数必须按 0 处理，分段与明细同源：" + aging);
    }

    @Test
    @DisplayName("仓库名为 null 时明细仍可序列化（Map.of 会拒绝 null 值）")
    void nullWarehouseNameStillProducesARow() {
        when(warehouseStockMapper.selectList(any())).thenReturn(List.of(
                stock("SKU-NO-NAME", 2, 400, null)));

        Map<String, Object> result = assertDoesNotThrow(() -> service.agingAnalysis(1L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) result.get("oldestTop10");
        assertEquals(1, top.size());
        assertEquals("", top.get(0).get("warehouse"), "缺名用空串表示，不能抛 NPE");
        assertEquals(400, top.get(0).get("days"));
    }
}
