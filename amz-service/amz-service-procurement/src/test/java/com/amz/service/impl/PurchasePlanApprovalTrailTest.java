package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.PurchaseApprovalMapper;
import com.amz.mapper.PurchaseOrderItemMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.PurchasePlanMapper;
import com.amz.mapper.SupplierMapper;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.PurchaseApproval;
import com.amz.model.PurchasePlan;
import com.amz.service.ProcurementService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购审批留痕（amz_purchase_approval）。
 * <p>
 * <b>补的缺口</b>：这张表此前全仓零引用——审批只改 {@code amz_purchase_plan.status}，
 * 而 status/approvedBy/approvedTime/remark 都是「当前值」，二次改批就把上一次的记录覆盖掉，
 * 审批过程实际不留痕。现在每次通过/驳回在同一条事务里写一行留痕，写不上就整体回滚，
 * 不允许出现「状态已 APPROVED 但过程无痕」。
 * <p>
 * 已知残留（不掩盖）：{@code operator} 仍是调用方自报的请求参数，可信身份（JWT 里的 user id）
 * 没有对应列，要记录就是改表结构，另立项处理。
 */
@DisplayName("采购审批留痕：每次改批都留下一行，写不上就整体失败")
@ExtendWith(MockitoExtension.class)
class PurchasePlanApprovalTrailTest {

    @Mock
    private PurchasePlanMapper purchasePlanMapper;
    @Mock
    private PurchaseApprovalMapper purchaseApprovalMapper;
    @Mock
    private PurchaseOrderMapper purchaseOrderMapper;
    @Mock
    private PurchaseOrderItemMapper purchaseOrderItemMapper;
    @Mock
    private SupplierProductMapper supplierProductMapper;
    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private ProcurementService procurementService;

    @InjectMocks
    private PurchasePlanServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PurchaseApproval.class);
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static PurchasePlan pendingPlan(Long id, Long shopId) {
        PurchasePlan plan = new PurchasePlan();
        plan.setId(id);
        plan.setPlanNo("PP-20261003-0001");
        plan.setShopId(shopId);
        plan.setSku("SKU-1");
        plan.setPlannedQty(10);
        plan.setStatus("PENDING_APPROVAL");
        return plan;
    }

    @Test
    @DisplayName("通过：写一行 APPROVE，店铺与单据号取自计划本行而不是请求参数")
    void approvalWritesApproveTrail() {
        when(purchasePlanMapper.selectById(7L)).thenReturn(pendingPlan(7L, 1L));
        when(purchaseApprovalMapper.insert(any(PurchaseApproval.class))).thenReturn(1);
        ArgumentCaptor<PurchaseApproval> trail = ArgumentCaptor.forClass(PurchaseApproval.class);

        PurchasePlan result = service.approve(7L, "  张经理 ", true, "  价格已核 ");

        assertEquals("APPROVED", result.getStatus());
        verify(purchaseApprovalMapper).insert(trail.capture());
        PurchaseApproval row = trail.getValue();
        assertEquals("PLAN", row.getRefType());
        assertEquals(7L, row.getRefId());
        assertEquals(1L, row.getShopId(), "留痕的店铺必须跟着计划走，不能信请求参数");
        assertEquals("APPROVE", row.getAction());
        assertEquals("张经理", row.getOperator());
        assertEquals("价格已核", row.getComment());
        assertTrue(row.getCreateTime() != null, "留痕要有时间");
    }

    @Test
    @DisplayName("驳回：写 REJECT，且意见为空时存 null 而不是空串")
    void rejectionWritesRejectTrail() {
        when(purchasePlanMapper.selectById(8L)).thenReturn(pendingPlan(8L, 1L));
        when(purchaseApprovalMapper.insert(any(PurchaseApproval.class))).thenReturn(1);
        ArgumentCaptor<PurchaseApproval> trail = ArgumentCaptor.forClass(PurchaseApproval.class);

        service.approve(8L, "李四", false, "   ");

        verify(purchaseApprovalMapper).insert(trail.capture());
        assertEquals("REJECT", trail.getValue().getAction());
        assertNull(trail.getValue().getComment());
    }

    @Test
    @DisplayName("留痕写不上：整体报错，不允许状态改了而过程无痕")
    void failedTrailInsertAbandonsTheApproval() {
        when(purchasePlanMapper.selectById(7L)).thenReturn(pendingPlan(7L, 1L));
        when(purchaseApprovalMapper.insert(any(PurchaseApproval.class))).thenReturn(0);

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.approve(7L, "张经理", true, "ok"));
        assertTrue(ex.getMessage().contains("回滚"), ex.getMessage());
    }

    @Test
    @DisplayName("状态门不满足时一行都不写")
    void invalidStateWritesNoTrail() {
        PurchasePlan draft = pendingPlan(9L, 1L);
        draft.setStatus("DRAFT");
        when(purchasePlanMapper.selectById(9L)).thenReturn(draft);

        assertThrows(CodeErrorException.class, () -> service.approve(9L, "张经理", true, "ok"));
        verify(purchaseApprovalMapper, never()).insert(any(PurchaseApproval.class));
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("操作人为空：留痕表的 operator 是 NOT NULL，直接拒并什么都不改")
    void blankOperatorIsRejectedBeforeAnyWrite() {
        AttrIsNullException ex = assertThrows(AttrIsNullException.class,
                () -> service.approve(7L, "   ", true, "ok"));
        // 只断言异常类型不够：「计划不存在」也是 AttrIsNullException，会放过「不校验操作人」的改动
        assertTrue(ex.getMessage().contains("操作人"), ex.getMessage());
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
        verify(purchaseApprovalMapper, never()).insert(any(PurchaseApproval.class));
    }

    @Test
    @DisplayName("别人店铺的计划：读留痕也拒，且不查留痕表")
    void trailReadIsScopedToPlanOwner() {
        when(purchasePlanMapper.selectById(9L)).thenReturn(pendingPlan(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.listPlanApprovals(9L));
        verify(purchaseApprovalMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("留痕按 PLAN + 计划 id 查，条件来自计划本行")
    void trailQueryFiltersByPlanReference() {
        when(purchasePlanMapper.selectById(7L)).thenReturn(pendingPlan(7L, 1L));
        when(purchaseApprovalMapper.selectList(any())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<PurchaseApproval>> wrapper =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);

        List<PurchaseApproval> rows = service.listPlanApprovals(7L);

        assertEquals(0, rows.size());
        verify(purchaseApprovalMapper).selectList(wrapper.capture());
        // MyBatis-Plus 只有在生成 SQL 片段时才把条件值落到参数表里，
        // 所以要先注册实体元数据，再读 getCustomSqlSegment + 参数（沿用 AdPagingContractTest 的做法）
        String sql = wrapper.getValue().getCustomSqlSegment();
        var params = wrapper.getValue().getParamNameValuePairs().values();
        assertTrue(sql.contains("ref_type"), "缺少 ref_type 条件：" + sql);
        assertTrue(sql.contains("ref_id"), "缺少 ref_id 条件：" + sql);
        assertTrue(params.contains("PLAN"), "必须按 ref_type=PLAN 过滤：" + params);
        assertTrue(params.contains(7L), "必须按 ref_id=计划 id 过滤：" + params);
    }
}
