package com.amz.service.impl;

import com.amz.client.SpApiFinanceClient;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.SettlementDetail;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("结算明细列表游标分页测试")
class SettlementServiceImplPagingTest {

    @Mock
    private SpApiFinanceClient spApiFinanceClient;
    @Mock
    private SettlementDetailMapper settlementDetailMapper;

    @InjectMocks
    private SettlementServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SettlementDetail.class);
    }

    @Test
    @DisplayName("结算明细：探测行不返回，cursor 取本页最后一条可见行")
    void truncatedPageDropsProbeRowAndUsesLastVisibleCursor() {
        when(settlementDetailMapper.selectList(any())).thenReturn(List.of(
                detail(30L, "111-1"), detail(29L, "111-1"), detail(28L, "111-1")));

        PageResult<SettlementDetail> page =
                service.list(1L, "111-1", PageRequest.first(2));

        assertEquals(List.of(30L, 29L), page.items().stream().map(SettlementDetail::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());
    }

    @Test
    @DisplayName("结算明细：订单过滤与 id 游标同时生效，并带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void orderFilterAndCursorUseKeysetWithExplicitLimit() {
        when(settlementDetailMapper.selectList(any())).thenReturn(List.of(detail(5L, "111-1")));
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor(900L));

        service.list(1L, "111-1", request);

        ArgumentCaptor<LambdaQueryWrapper<SettlementDetail>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(settlementDetailMapper).selectList(captor.capture());
        LambdaQueryWrapper<SettlementDetail> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "结算明细列表必须禁止无界 selectList：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900L), segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("111-1"), segment);
    }

    @Test
    @DisplayName("结算明细：未取满一页时不误报截断")
    void untruncatedPageHasNoCursor() {
        when(settlementDetailMapper.selectList(any())).thenReturn(List.of(detail(2L, null)));

        PageResult<SettlementDetail> page =
                service.list(1L, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertNull(page.nextCursor());
    }

    private static SettlementDetail detail(Long id, String amazonOrderId) {
        SettlementDetail detail = new SettlementDetail();
        detail.setId(id);
        detail.setShopId(1L);
        detail.setAmazonOrderId(amazonOrderId);
        return detail;
    }
}