package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.mapper.WarehouseMapper;
import com.amz.model.WarehouseInventory;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("仓库库存游标分页测试")
class WarehouseInventoryPagingTest {

    @Mock
    private WarehouseMapper warehouseMapper;

    @Mock
    private WarehouseInventoryMapper inventoryMapper;

    @InjectMocks
    private WarehouseServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WarehouseInventory.class);
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
    @DisplayName("库存列表：命中 size+1 时丢弃探测行，并用本页最后可见行生成 cursor")
    void truncatedPageDropsProbeRowAndUsesLastVisibleCursor() {
        when(inventoryMapper.selectList(any())).thenReturn(List.of(
                inventory(30L), inventory(29L), inventory(28L)));

        PageResult<WarehouseInventory> page =
                service.listInventory(null, null, 1L, PageRequest.first(2));

        assertEquals(List.of(30L, 29L), page.items().stream().map(WarehouseInventory::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());
        assertNull(page.total(), "未做 COUNT(*)，总数必须是未知而不是 0");
    }

    @Test
    @DisplayName("库存列表：未取满一页时 truncated=false 且没有 cursor")
    void untruncatedPageHasNoCursor() {
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(2L), inventory(1L)));

        PageResult<WarehouseInventory> page =
                service.listInventory(7L, "SKU-1", 1L, PageRequest.first(2));

        assertEquals(2, page.items().size());
        assertFalse(page.truncated());
        assertNull(page.nextCursor());
    }

    @Test
    @DisplayName("库存列表：cursor 必须形成 id 下界，查询始终带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cursorUsesKeysetAndExplicitLimit() {
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(5L)));
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor(900L));

        service.listInventory(null, null, 1L, request);

        ArgumentCaptor<LambdaQueryWrapper<WarehouseInventory>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(inventoryMapper).selectList(captor.capture());
        LambdaQueryWrapper<WarehouseInventory> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "库存列表必须禁止无界 selectList：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900L),
                "游标必须作为 id 下界参与查询：" + wrapper.getParamNameValuePairs());
    }

    private static WarehouseInventory inventory(Long id) {
        WarehouseInventory inventory = new WarehouseInventory();
        inventory.setId(id);
        inventory.setShopId(1L);
        inventory.setWarehouseId(7L);
        inventory.setSku("SKU-" + id);
        return inventory;
    }
}