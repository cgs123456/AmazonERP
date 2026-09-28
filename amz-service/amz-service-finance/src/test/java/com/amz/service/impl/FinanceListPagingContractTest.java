package com.amz.service.impl;

import com.amz.client.SpApiFinanceClient;
import com.amz.mapper.FeeDiscrepancyMapper;
import com.amz.mapper.PaymentCollectionMapper;
import com.amz.mapper.ReimbursementClaimMapper;
import com.amz.mapper.SettlementDetailMapper;
import com.amz.model.FeeDiscrepancy;
import com.amz.model.PaymentCollection;
import com.amz.model.ReimbursementClaim;
import com.amz.model.SettlementDetail;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.FeeDiscrepancyService;
import com.amz.service.FinanceService;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("财务三类对外列表必须使用游标分页")
class FinanceListPagingContractTest {

    @Mock
    private SpApiFinanceClient spApiFinanceClient;
    @Mock
    private SettlementDetailMapper settlementDetailMapper;
    @Mock
    private FeeDiscrepancyMapper feeDiscrepancyMapper;
    @Mock
    private PaymentCollectionMapper paymentCollectionMapper;
    @Mock
    private ReimbursementClaimMapper reimbursementClaimMapper;
    @Mock
    private FinanceService financeService;

    @InjectMocks
    private FeeDiscrepancyServiceImpl feeDiscrepancyService;
    @InjectMocks
    private PaymentCollectionServiceImpl paymentCollectionService;
    @InjectMocks
    private ReimbursementClaimServiceImpl reimbursementClaimService;

    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FeeDiscrepancy.class);
        TableInfoHelper.initTableInfo(assistant, PaymentCollection.class);
        TableInfoHelper.initTableInfo(assistant, ReimbursementClaim.class);
    }

    @Test
    @DisplayName("费用差异：状态/类型过滤与 id 游标同时生效，探测行不返回")
    void feeDiscrepancyListUsesKeysetPaging() {
        when(feeDiscrepancyMapper.selectList(any())).thenReturn(List.of(
                feeDiscrepancy(30L, "CANDIDATE", "SIZE_TIER_JUMP"),
                feeDiscrepancy(29L, "CANDIDATE", "SIZE_TIER_JUMP"),
                feeDiscrepancy(28L, "CANDIDATE", "SIZE_TIER_JUMP")));

        PageResult<FeeDiscrepancy> page = feeDiscrepancyService.list(
                1L, "CANDIDATE", "SIZE_TIER_JUMP", PageRequest.of(2, PageRequest.encodeCursor(900L)));

        assertEquals(List.of(30L, 29L), page.items().stream().map(FeeDiscrepancy::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());
        assertWrapperHasLimitAndCursor(feeDiscrepancyMapper, 900L,
                "CANDIDATE", "SIZE_TIER_JUMP");
    }

    @Test
    @DisplayName("回款台账：状态过滤与 id 游标同时生效，探测行不返回")
    void paymentCollectionListUsesKeysetPaging() {
        when(paymentCollectionMapper.selectList(any())).thenReturn(List.of(
                paymentCollection(30L, "SETTLED"),
                paymentCollection(29L, "SETTLED"),
                paymentCollection(28L, "SETTLED")));

        PageResult<PaymentCollection> page = paymentCollectionService.list(
                1L, "settled", PageRequest.of(2, PageRequest.encodeCursor(900L)));

        assertEquals(List.of(30L, 29L), page.items().stream().map(PaymentCollection::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());
        assertWrapperHasLimitAndCursor(paymentCollectionMapper, 900L, "SETTLED");
    }

    @Test
    @DisplayName("索赔单：状态过滤与 id 游标同时生效，探测行不返回")
    void reimbursementClaimListUsesKeysetPaging() {
        when(reimbursementClaimMapper.selectList(any())).thenReturn(List.of(
                reimbursementClaim(30L, "REIMBURSED"),
                reimbursementClaim(29L, "REIMBURSED"),
                reimbursementClaim(28L, "REIMBURSED")));

        PageResult<ReimbursementClaim> page = reimbursementClaimService.list(
                1L, "reimbursed", PageRequest.of(2, PageRequest.encodeCursor(900L)));

        assertEquals(List.of(30L, 29L), page.items().stream().map(ReimbursementClaim::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());
        assertWrapperHasLimitAndCursor(reimbursementClaimMapper, 900L, "REIMBURSED");
    }

    @Test
    @DisplayName("回款台账：未取满一页时不误报截断")
    void untruncatedPaymentPageHasNoCursor() {
        when(paymentCollectionMapper.selectList(any())).thenReturn(List.of(paymentCollection(2L, "PENDING")));

        PageResult<PaymentCollection> page = paymentCollectionService.list(
                1L, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertEquals(null, page.nextCursor());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertWrapperHasLimitAndCursor(BaseMapper mapper, Long cursorId, String... values) {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<?> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "列表查询必须禁止无界 selectList：" + segment);
        if (cursorId != null) {
            assertTrue(wrapper.getParamNameValuePairs().containsValue(cursorId), segment);
        }
        for (String value : values) {
            assertTrue(wrapper.getParamNameValuePairs().containsValue(value), segment);
        }
    }

    private static FeeDiscrepancy feeDiscrepancy(Long id, String status, String type) {
        FeeDiscrepancy row = new FeeDiscrepancy();
        row.setId(id);
        row.setShopId(1L);
        row.setStatus(status);
        row.setDiscrepancyType(type);
        return row;
    }

    private static PaymentCollection paymentCollection(Long id, String status) {
        PaymentCollection row = new PaymentCollection();
        row.setId(id);
        row.setShopId(1L);
        row.setStatus(status);
        return row;
    }

    private static ReimbursementClaim reimbursementClaim(Long id, String status) {
        ReimbursementClaim row = new ReimbursementClaim();
        row.setId(id);
        row.setShopId(1L);
        row.setStatus(status);
        row.setClaimAmount(BigDecimal.ONE);
        return row;
    }
}