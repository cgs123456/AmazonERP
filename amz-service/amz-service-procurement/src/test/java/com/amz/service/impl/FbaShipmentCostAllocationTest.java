package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.FbaShipmentItemMapper;
import com.amz.mapper.FbaShipmentMapper;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.model.FbaShipment;
import com.amz.model.FbaShipmentItem;
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

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 整单费用分摊对脏明细的容错。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 代码审查 Medium）</b>：
 * {@code allocateCosts} 用 {@code items.stream().mapToInt(FbaShipmentItem::getQuantity).sum()}
 * —— 任一行 quantity 为 null 就拆箱 NPE；而 {@code totalQty > 0} 只按总量校验，
 * 单行 quantity=0 仍然进得来循环，末尾 {@code itemTotalCost.divide(BigDecimal.valueOf(quantity))}
 * 直接抛 ArithmeticException。两者都会让整个 {@code @Transactional} 分摊回滚：
 * 一条脏明细挡住整单成本入账。
 * 同文件 {@code :115} 与 {@code :335} 都已按"null/非正"处理，唯独这里漏了。
 */
@DisplayName("货件费用分摊：脏数量行不得拖垮整单分摊")
@ExtendWith(MockitoExtension.class)
class FbaShipmentCostAllocationTest {

    @Mock
    private FbaShipmentMapper fbaShipmentMapper;

    @Mock
    private FbaShipmentItemMapper fbaShipmentItemMapper;

    @Mock
    private InventoryBatchMapper inventoryBatchMapper;

    @InjectMocks
    private FbaShipmentServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FbaShipment.class);
        TableInfoHelper.initTableInfo(assistant, FbaShipmentItem.class);
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
    @DisplayName("数量为 null 或 0 的明细行被跳过；整单成本仍全部分摊到有效行")
    void skipsDirtyRowsAndStillAllocatesFullCost() {
        FbaShipment shipment = new FbaShipment();
        shipment.setId(77L);
        shipment.setShopId(1L);
        shipment.setFreightCost(new BigDecimal("100.00"));
        when(fbaShipmentMapper.selectById(77L)).thenReturn(shipment);
        when(fbaShipmentItemMapper.selectList(any())).thenReturn(List.of(
                item("S-OK", 2),
                item("S-NULL-QTY", null),
                item("S-ZERO-QTY", 0)));

        service.allocateCosts(77L);

        ArgumentCaptor<FbaShipmentItem> updated = ArgumentCaptor.forClass(FbaShipmentItem.class);
        verify(fbaShipmentItemMapper, times(1)).updateById(updated.capture());
        FbaShipmentItem ok = updated.getValue();
        assertEquals("S-OK", ok.getSku(), "只有数量有效的行该被写入分摊结果");
        assertEquals(new BigDecimal("100.00"), ok.getFreightAllocation(),
                "全部运费应落在这一行（其余两行数量为 0，比例本就不该参与）");
    }

    private static FbaShipmentItem item(String sku, Integer quantity) {
        FbaShipmentItem item = new FbaShipmentItem();
        item.setFbaShipmentId(77L);
        item.setSku(sku);
        item.setQuantity(quantity);
        item.setUnitCost(BigDecimal.ONE);
        return item;
    }
}
