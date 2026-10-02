package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.model.WarehouseInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库位码更新的入参与归属校验。
 * <p>
 * 前端「改库位」会把这一格直接暴露成可编辑输入框，参数又是 query 里的裸字符串：
 * {@code ?locationCode=} 能通过「参数必填」的检查，却会把库位清空；
 * 超过 DDL 的 VARCHAR(50) 则会撞到 MySQL 严格模式变成 500。
 * 两种都必须在写库之前被拒绝。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("海外仓库位更新校验")
class WarehouseLocationUpdateTest {

    @Mock
    private WarehouseInventoryMapper inventoryMapper;

    @InjectMocks
    private WarehouseServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "inventoryMapper", inventoryMapper);
        UserContext.setUserId(5);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(2L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private WarehouseInventory row(Long shopId) {
        WarehouseInventory inv = new WarehouseInventory();
        inv.setId(77L);
        inv.setShopId(shopId);
        inv.setSku("SKU-77");
        inv.setLocationCode("A-01-02");
        return inv;
    }

    @Test
    @DisplayName("空串与空白不得清空库位")
    void rejectsBlankLocation() {
        when(inventoryMapper.selectById(77L)).thenReturn(row(2L));

        CodeErrorException empty = assertThrows(CodeErrorException.class,
                () -> service.updateLocationCode(77L, ""));
        assertTrue(empty.getMessage().contains("库位码"), "报错要指向库位码：" + empty.getMessage());

        assertThrows(CodeErrorException.class, () -> service.updateLocationCode(77L, "   "));
        assertThrows(CodeErrorException.class, () -> service.updateLocationCode(77L, null));
        verify(inventoryMapper, never()).updateById(any(WarehouseInventory.class));
    }

    @Test
    @DisplayName("超过列宽 50 字符直接拒绝，不给数据库报错")
    void rejectsOverlongLocation() {
        when(inventoryMapper.selectById(77L)).thenReturn(row(2L));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.updateLocationCode(77L, "X".repeat(51)));
        assertTrue(ex.getMessage().contains("50"), "要写清上限：" + ex.getMessage());
        verify(inventoryMapper, never()).updateById(any(WarehouseInventory.class));
    }

    @Test
    @DisplayName("合法库位去除首尾空格后写回，并返回更新后的行")
    void acceptsAndTrims() {
        when(inventoryMapper.selectById(77L)).thenReturn(row(2L));

        WarehouseInventory updated = service.updateLocationCode(77L, "  B-12-04  ");

        assertEquals("B-12-04", updated.getLocationCode());
        verify(inventoryMapper).updateById(updated);
    }

    @Test
    @DisplayName("别人店铺的库存行不能改")
    void rejectsForeignShopRow() {
        when(inventoryMapper.selectById(77L)).thenReturn(row(3L));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.updateLocationCode(77L, "B-12-04"));
        assertTrue(ex.getMessage().contains("无权"));
        verify(inventoryMapper, never()).updateById(any(WarehouseInventory.class));
    }
}
