package com.amz.service.impl;

import com.amz.mapper.PurchaseOrderMapper;
import com.amz.model.PurchaseOrder;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证用采购单读取（{@code /procurement/order/voucher-source}）的口径测试。
 * <p>
 * 这个端点是财务 PROCUREMENT 凭证的唯一数据源，因此它必须只交「成本已确认」的单：
 * 草稿或在途的单被读进来，等于把没花出去的钱记成成本；
 * 反过来漏掉 RECEIVED/COMPLETED，成本又会被少记。两个方向都要被断言钉住。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("凭证用采购单读取口径")
class ProcurementVoucherSourceTest {

    @Mock
    private PurchaseOrderMapper purchaseOrderMapper;

    @InjectMocks
    private ProcurementServiceImpl procurementService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PurchaseOrder.class);
    }

    private PurchaseOrder po(long id, String status) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId(id);
        o.setShopId(7L);
        o.setOrderNo("PO-" + id);
        o.setSku("SKU-1");
        o.setQuantity(2);
        o.setUnitPrice(new BigDecimal("10.00"));
        o.setTotalAmount(new BigDecimal("20.00"));
        o.setStatus(status);
        return o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedParams(LambdaQueryWrapper<PurchaseOrder> wrapper) {
        return (Map<String, Object>) wrapper.getParamNameValuePairs();
    }

    @Test
    @DisplayName("查询条件锁定店铺 + 三种成本可确认状态，且按 id 倒序游标翻页")
    void filtersToCostRecognisedStatuses() {
        when(purchaseOrderMapper.selectList(any())).thenReturn(Collections.emptyList());

        procurementService.listOrdersForVoucher(7L, PageRequest.first(50));

        ArgumentCaptor<LambdaQueryWrapper<PurchaseOrder>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(purchaseOrderMapper).selectList(captor.capture());
        LambdaQueryWrapper<PurchaseOrder> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("shop_id ="), "应过滤店铺，实际 SQL 片段: " + sql);
        assertTrue(sql.contains("status IN"), "应过滤状态集合，实际 SQL 片段: " + sql);
        assertTrue(sql.contains("ORDER BY"), "应显式排序以保证游标稳定，实际 SQL 片段: " + sql);
        Collection<Object> values = capturedParams(wrapper).values();
        assertTrue(values.contains("QC_PASSED") && values.contains("RECEIVED") && values.contains("COMPLETED"),
                "三种成本可确认状态都要在内，实际参数: " + values);
        assertFalse(values.contains("DRAFT"), "草稿单不应被当作成本来源");
        assertFalse(values.contains("CANCELED"), "已取消单不应产生采购成本凭证");
        assertTrue(sql.contains("LIMIT"), "必须有页大小上限，不允许无界拉取");
    }

    @Test
    @DisplayName("带游标时用 id < cursor，继续往更早的单翻页")
    void appliesCursorAsIdLowerBound() {
        when(purchaseOrderMapper.selectList(any())).thenReturn(Collections.emptyList());

        procurementService.listOrdersForVoucher(7L,
                PageRequest.of(50, PageRequest.encodeCursor(123L)));

        ArgumentCaptor<LambdaQueryWrapper<PurchaseOrder>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(purchaseOrderMapper).selectList(captor.capture());
        LambdaQueryWrapper<PurchaseOrder> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("id <"), "游标应转成 id 下界，实际: " + wrapper.getSqlSegment());
        assertTrue(capturedParams(wrapper).values().contains(123L));
    }

    @Test
    @DisplayName("返回 PageResult 并带 nextCursor，调用方能翻完而不是只拿首页")
    void returnsCursorPageMeta() {
        // 服务按 size+1 探测：给满 3 行而 size=2，说明还有下一页
        when(purchaseOrderMapper.selectList(any()))
                .thenReturn(List.of(po(10L, "QC_PASSED"), po(9L, "RECEIVED"), po(8L, "COMPLETED")));

        PageResult<PurchaseOrder> page = procurementService.listOrdersForVoucher(7L, PageRequest.of(2, null));

        assertEquals(2, page.items().size());
        assertTrue(page.meta().isTruncated(), "多探测到 1 行说明还有下一页，必须标记截断");
        assertEquals(PageRequest.encodeCursor(9L), page.meta().getNextCursor());
    }
}
