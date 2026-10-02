package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.PurchaseOrderItemMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.PurchaseApprovalMapper;
import com.amz.mapper.PurchasePlanMapper;
import com.amz.mapper.SupplierMapper;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.PurchasePlan;
import com.amz.model.Supplier;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.ProcurementService;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 采购计划服务多租户隔离测试。
 * <p>
 * 回归：计划创建未校验 Body shopId，提交/审批/转换/取消仅按 planId 操作，
 * ShopScoped 切面无法拦截资源 ID，形成跨店审批与转单风险。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("采购计划服务多租户隔离测试")
class PurchasePlanServiceImplTest {

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

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("创建采购计划：拒绝请求体中的他店 shopId")
    void createPlanRejectsForeignShopInBody() {
        authenticateForShop(1L);
        PurchasePlan input = plan(null, 2L);

        assertThrows(CodeErrorException.class, () -> service.createPlan(input));
        verifyNoInteractions(purchasePlanMapper);
    }

    @Test
    @DisplayName("创建采购计划：拒绝引用他店供应商")
    void createPlanRejectsForeignSupplierReference() {
        authenticateForShop(1L);
        when(supplierMapper.selectById(9L)).thenReturn(supplier(9L, 2L));
        PurchasePlan input = plan(null, 1L);
        input.setSupplierId(9L);
        input.setUnitPrice(new BigDecimal("10.00"));

        assertThrows(CodeErrorException.class, () -> service.createPlan(input));
        verify(purchasePlanMapper, never()).insert(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("提交审批：拒绝他店采购计划")
    void submitForApprovalRejectsForeignPlan() {
        authenticateForShop(1L);
        when(purchasePlanMapper.selectById(9L)).thenReturn(plan(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.submitForApproval(9L));
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("审批：拒绝他店采购计划")
    void approveRejectsForeignPlan() {
        authenticateForShop(1L);
        when(purchasePlanMapper.selectById(9L)).thenReturn(plan(9L, 2L));

        assertThrows(CodeErrorException.class,
                () -> service.approve(9L, "attacker", true, "approved"));
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("转为采购订单：拒绝他店采购计划且不创建订单")
    void convertToOrderRejectsForeignPlan() {
        authenticateForShop(1L);
        when(purchasePlanMapper.selectById(9L)).thenReturn(plan(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.convertToOrder(9L));
        verifyNoInteractions(procurementService);
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("取消采购计划：拒绝他店采购计划")
    void cancelPlanRejectsForeignPlan() {
        authenticateForShop(1L);
        when(purchasePlanMapper.selectById(9L)).thenReturn(plan(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.cancelPlan(9L));
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("提交审批：非草稿状态按业务错误返回")
    void submitForApprovalRejectsInvalidState() {
        authenticateForShop(1L);
        PurchasePlan approved = plan(9L, 1L);
        approved.setStatus("APPROVED");
        when(purchasePlanMapper.selectById(9L)).thenReturn(approved);

        assertThrows(CodeErrorException.class, () -> service.submitForApproval(9L));
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("审批：非待审批状态按业务错误返回")
    void approveRejectsInvalidState() {
        authenticateForShop(1L);
        PurchasePlan draft = plan(9L, 1L);
        when(purchasePlanMapper.selectById(9L)).thenReturn(draft);

        assertThrows(CodeErrorException.class,
                () -> service.approve(9L, "operator", true, "approved"));
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("转单：非已审批状态按业务错误返回")
    void convertToOrderRejectsInvalidState() {
        authenticateForShop(1L);
        PurchasePlan draft = plan(9L, 1L);
        when(purchasePlanMapper.selectById(9L)).thenReturn(draft);

        assertThrows(CodeErrorException.class, () -> service.convertToOrder(9L));
        verifyNoInteractions(procurementService);
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }

    @Test
    @DisplayName("取消：已转换状态按业务错误返回")
    void cancelPlanRejectsInvalidState() {
        authenticateForShop(1L);
        PurchasePlan converted = plan(9L, 1L);
        converted.setStatus("CONVERTED");
        when(purchasePlanMapper.selectById(9L)).thenReturn(converted);

        assertThrows(CodeErrorException.class, () -> service.cancelPlan(9L));
        verify(purchasePlanMapper, never()).updateById(any(PurchasePlan.class));
    }
    @Test
    @DisplayName("查询采购计划列表：游标分页且探测行不返回")
    void listPlansUsesCursorPagination() {
        authenticateForShop(1L);
        when(purchasePlanMapper.selectList(any())).thenReturn(List.of(
                plan(30L, 1L), plan(29L, 1L), plan(28L, 1L)));

        PageResult<PurchasePlan> page = service.listPlans(1L, null, PageRequest.first(2));

        assertEquals(List.of(30L, 29L),
                page.items().stream().map(PurchasePlan::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());
    }

    @Test
    @DisplayName("查询采购计划列表：拒绝他店 shopId")
    void listPlansRejectsForeignShop() {
        authenticateForShop(1L);

        assertThrows(CodeErrorException.class, () -> service.listPlans(2L, null, PageRequest.first(50)));
        verify(purchasePlanMapper, never()).selectList(any());
    }

    private static void authenticateForShop(Long shopId) {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(shopId));
    }

    private static PurchasePlan plan(Long id, Long shopId) {
        PurchasePlan plan = new PurchasePlan();
        plan.setId(id);
        plan.setShopId(shopId);
        plan.setSku("SKU-1");
        plan.setPlannedQty(10);
        plan.setStatus("DRAFT");
        return plan;
    }

    private static Supplier supplier(Long id, Long shopId) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setShopId(shopId);
        supplier.setSupplierName("supplier-" + id);
        return supplier;
    }
}