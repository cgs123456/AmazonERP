package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.InboundOrderMapper;
import com.amz.model.InboundOrder;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.WarehouseService;
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
@DisplayName("入库单游标分页测试")
class InboundServiceImplPagingTest {

    @Mock
    private InboundOrderMapper inboundOrderMapper;

    @Mock
    private WarehouseService warehouseService;

    @InjectMocks
    private InboundServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                InboundOrder.class);
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
    @DisplayName("入库列表：探测行不返回，cursor 取本页最后一条可见行")
    void truncatedPageDropsProbeRowAndUsesLastVisibleCursor() {
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of(
                order(40L), order(39L), order(38L)));

        PageResult<InboundOrder> page =
                service.listInboundOrders(1L, "PENDING", PageRequest.first(2));

        assertEquals(List.of(40L, 39L), page.items().stream().map(InboundOrder::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(39L), page.nextCursor());
    }

    @Test
    @DisplayName("入库列表：未取满一页时不误报截断")
    void untruncatedPageHasNoCursor() {
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of(order(2L)));

        PageResult<InboundOrder> page =
                service.listInboundOrders(1L, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertNull(page.nextCursor());
    }

    @Test
    @DisplayName("入库列表：status 过滤与 id 游标下界同时生效，并带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cursorAndStatusUseKeysetWithExplicitLimit() {
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of(order(5L)));
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor(900L));

        service.listInboundOrders(1L, "PENDING", request);

        ArgumentCaptor<LambdaQueryWrapper<InboundOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(inboundOrderMapper).selectList(captor.capture());
        LambdaQueryWrapper<InboundOrder> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "入库列表必须禁止无界 selectList：" + segment);
        assertTrue(segment.contains("status"), "status 过滤必须保留：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900L),
                "游标必须作为 id 下界参与查询：" + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getParamNameValuePairs().containsValue("PENDING"),
                "status 过滤参数必须参与查询：" + wrapper.getParamNameValuePairs());
    }

    private static InboundOrder order(Long id) {
        InboundOrder order = new InboundOrder();
        order.setId(id);
        order.setShopId(1L);
        order.setStatus("PENDING");
        return order;
    }
}