package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.dto.BatchCostSummary;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.InventoryBatchMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("采购批次成本聚合服务测试")
class InventoryBatchCostSummaryServiceTest {

    @Mock
    private InventoryBatchMapper inventoryBatchMapper;

    @InjectMocks
    private FbaShipmentServiceImpl service;

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
    @DisplayName("聚合查询：直接返回数据库 SUM 结果，不拉取批次明细")
    void returnsDatabaseAggregate() {
        BatchCostSummary summary = new BatchCostSummary();
        summary.setBatchCount(2);
        summary.setTotalQuantity(120);
        summary.setTotalBatchCost(new BigDecimal("600.00"));
        when(inventoryBatchMapper.sumActiveBatchCost(1L, "SKU-A")).thenReturn(summary);

        BatchCostSummary result = service.getBatchCostSummary(1L, "SKU-A");

        assertEquals(2, result.getBatchCount());
        assertEquals(120, result.getTotalQuantity());
        assertEquals(0, new BigDecimal("600.00").compareTo(result.getTotalBatchCost()));
        verify(inventoryBatchMapper, never()).selectList(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("越权店铺：拒绝且不得触碰数据库")
    void rejectsUnauthorizedShopBeforeQuery() {
        UserContext.setShops(List.of(2L));

        assertThrows(CodeErrorException.class, () -> service.getBatchCostSummary(1L, "SKU-A"));

        verifyNoInteractions(inventoryBatchMapper);
    }
}