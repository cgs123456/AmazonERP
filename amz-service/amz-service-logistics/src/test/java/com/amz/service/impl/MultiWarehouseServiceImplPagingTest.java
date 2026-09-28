package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.InventoryAlertMapper;
import com.amz.mapper.WarehouseStockMapper;
import com.amz.model.WarehouseStock;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("多仓库存游标分页与聚合截断测试")
class MultiWarehouseServiceImplPagingTest {

    @Mock
    private WarehouseStockMapper warehouseStockMapper;

    @Mock
    private InventoryAlertMapper inventoryAlertMapper;

    @InjectMocks
    private MultiWarehouseServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WarehouseStock.class);
    }

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(9);
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    @DisplayName("多仓列表：探测行不返回，复合 cursor 取本页最后可见行")
    void listStockUsesCompositeCursor() {
        WarehouseStock first = stock(11L, 7L, "SKU-1");
        WarehouseStock probe = stock(10L, 7L, "SKU-2");
        when(warehouseStockMapper.selectList(any())).thenReturn(List.of(first, probe));

        PageResult<WarehouseStock> page =
                service.listStock(1L, null, null, PageRequest.first(1));

        assertEquals(List.of(11L), page.items().stream().map(WarehouseStock::getId).toList());
        assertTrue(page.truncated());
        PageRequest next = PageRequest.of(1, page.nextCursor());
        assertTrue(next.payload().startsWith("7\u001F"), "复合游标必须从 warehouseId 开始");
        assertTrue(next.payload().endsWith("\u001F11"), "复合游标必须以本页最后一条 id 结尾");
    }

    @Test
    @DisplayName("多仓列表：复合 cursor 形成三列 keyset 下界，并带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listStockCursorUsesCompositeKeyset() {
        when(warehouseStockMapper.selectList(any())).thenReturn(List.of(stock(20L, 8L, "SKU-3")));
        String payload = "7\u001F" + base64("SKU-2") + "\u001F11";
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor(payload));

        service.listStock(1L, null, null, request);

        ArgumentCaptor<LambdaQueryWrapper<WarehouseStock>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(warehouseStockMapper).selectList(captor.capture());
        LambdaQueryWrapper<WarehouseStock> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("warehouse_id"), "复合游标必须包含仓库键：" + segment);
        assertTrue(segment.contains("sku"), "复合游标必须包含 SKU 键：" + segment);
        assertTrue(segment.contains("LIMIT"), "多仓列表必须带显式 LIMIT：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("SKU-2"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(11L));
    }

    @Test
    @DisplayName("多仓列表：损坏的复合 cursor 必须报错，不能静默回退首页")
    void listStockRejectsInvalidCursor() {
        PageRequest bad = PageRequest.of(10, PageRequest.encodeCursor("garbage"));
        assertThrows(InvalidParamException.class, () -> service.listStock(1L, null, null, bad));
    }

    @Test
    @DisplayName("全局库存视图：扫描触顶必须返回 truncation 证据，不能假装聚合完整")
    void globalInventoryViewExposesScanTruncation() {
        List<WarehouseStock> rows = new ArrayList<>();
        for (int i = 0; i <= MultiWarehouseServiceImpl.AGGREGATION_SCAN_LIMIT; i++) {
            rows.add(stock((long) (i + 1), 7L, "SKU-" + i));
        }
        when(warehouseStockMapper.selectList(any())).thenReturn(rows);

        Map<String, Object> result = service.globalInventoryView(1L, null);

        assertEquals(Boolean.TRUE, result.get("stocksTruncated"));
        assertEquals(MultiWarehouseServiceImpl.AGGREGATION_SCAN_LIMIT, result.get("scannedStockCount"));
        assertEquals(MultiWarehouseServiceImpl.AGGREGATION_SCAN_LIMIT,
                ((List<?>) result.get("details")).size());
    }

    @Test
    @DisplayName("预警检查：库存扫描触顶必须暴露截断标志，避免漏报被当成无风险")
    void checkAlertsExposesStockScanTruncation() {
        when(inventoryAlertMapper.selectList(any())).thenReturn(List.of());
        List<WarehouseStock> rows = new ArrayList<>();
        for (int i = 0; i <= MultiWarehouseServiceImpl.AGGREGATION_SCAN_LIMIT; i++) {
            rows.add(stock((long) (i + 1), 7L, "SKU-" + i));
        }
        when(warehouseStockMapper.selectList(any())).thenReturn(rows);

        Map<String, Object> result = service.checkAlerts(1L);

        assertEquals(Boolean.TRUE, result.get("stocksTruncated"));
        assertEquals(MultiWarehouseServiceImpl.AGGREGATION_SCAN_LIMIT, result.get("scannedStockCount"));
    }

    private static WarehouseStock stock(Long id, Long warehouseId, String sku) {
        WarehouseStock stock = new WarehouseStock();
        stock.setId(id);
        stock.setShopId(1L);
        stock.setWarehouseId(warehouseId);
        stock.setWarehouseName("WH-" + warehouseId);
        stock.setWarehouseType("THIRD_PARTY");
        stock.setSku(sku);
        stock.setAvailableQty(0);
        stock.setReservedQty(0);
        stock.setInboundQty(0);
        stock.setTotalValue(java.math.BigDecimal.ZERO);
        stock.setDaysInStock(0);
        return stock;
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}