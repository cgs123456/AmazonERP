package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.EmailTemplateMapper;
import com.amz.mapper.NegativeReviewMapper;
import com.amz.mapper.RmaMapper;
import com.amz.model.Rma;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RMA 退货单服务（从 CustomerEmailServiceImpl 拆出后独立成类）。
 * <p>
 * <b>为什么必须有自己的测试</b>：拆分时最容易犯的错误是「行为悄悄变了而测试跟着搬过去」。
 * 这里保留拆分前的两条关键断言（越权拒绝、分页带上限），并补一条创建路径的默认值，
 * 确保拆出去的是<b>类型边界</b>而不是行为。
 * <p>
 * 未覆盖：{@code createRma} 的 shopId 为空校验（{@code AttrIsNullException}）——
 * 与越权拒绝走同一条入口，且 AttrIsNullException 的抛出条件只是 null 判断。
 */
@DisplayName("RMA 退货：越权拒绝、分页有界、创建填默认值")
class RmaServiceImplTest {

    private RmaMapper rmaMapper;
    private RmaServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Rma.class);
    }

    @BeforeEach
    void setUp() {
        rmaMapper = mock(RmaMapper.class);
        service = new RmaServiceImpl();
        ReflectionTestUtils.setField(service, "rmaMapper", rmaMapper);
        ReflectionTestUtils.setField(service, "support",
                new CustomerEmailSupport(mock(EmailTemplateMapper.class),
                        mock(NegativeReviewMapper.class), rmaMapper));
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("getRma/updateRmaStatus：非授权店铺应拒绝")
    void deniedForOtherShop() {
        UserContext.setUserId(1);
        UserContext.setShops(List.of(1L));

        Rma rma = new Rma();
        rma.setId(9L);
        rma.setShopId(99L);
        when(rmaMapper.selectById(9L)).thenReturn(rma);

        assertThrows(IllegalStateException.class, () -> service.getRma(9L));
        assertThrows(IllegalStateException.class, () -> service.updateRmaStatus(9L, "RESOLVED"));
    }

    @Test
    @DisplayName("RMA 列表：必须带显式 LIMIT 并暴露截断事实")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listIsBounded() {
        when(rmaMapper.selectList(any())).thenReturn(List.of(
                rma(12L), rma(11L), rma(10L)));

        PageResult<Rma> page = service.listRmas(1L, "PENDING", PageRequest.first(2));

        assertEquals(List.of(12L, 11L), page.items().stream().map(Rma::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(11L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<Rma>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(rmaMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("LIMIT"),
                "分页必须下推到 SQL，不能整表读进内存再截断");
    }

    @Test
    @DisplayName("创建：默认值补齐与单号生成（状态 PENDING / 类型 RETURN / 金额 0）")
    void createFillsDefaults() {
        UserContext.setUserId(1);
        UserContext.setShops(List.of(1L));

        Rma input = new Rma();
        input.setShopId(1L);
        input.setAmazonOrderId("O1");
        Rma created = service.createRma(input);

        assertEquals("PENDING", created.getStatus());
        assertEquals("RETURN", created.getReturnType());
        assertEquals(0, created.getLabelCost().compareTo(java.math.BigDecimal.ZERO));
        assertEquals(0, created.getRefundAmount().compareTo(java.math.BigDecimal.ZERO));
        assertTrue(created.getRmaNo().startsWith("RMA"), created.getRmaNo());
        org.mockito.Mockito.verify(rmaMapper).insert(created);
    }

    @Test
    @DisplayName("创建：非授权店铺拒绝（shopId 只在请求体，切面拦不到）")
    void createDeniedForOtherShop() {
        UserContext.setUserId(1);
        UserContext.setShops(List.of(1L));

        Rma rma = new Rma();
        rma.setShopId(99L);
        rma.setAmazonOrderId("O1");

        assertThrows(IllegalStateException.class, () -> service.createRma(rma));
        org.mockito.Mockito.verify(rmaMapper, org.mockito.Mockito.never()).insert(any(Rma.class));
    }

    private static Rma rma(Long id) {
        Rma value = new Rma();
        value.setId(id);
        value.setShopId(1L);
        return value;
    }
}
