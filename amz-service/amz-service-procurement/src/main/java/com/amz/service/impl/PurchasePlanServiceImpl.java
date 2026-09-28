package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.util.BizNoGenerator;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.PurchasePlanMapper;
import com.amz.mapper.PurchaseOrderItemMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.SupplierMapper;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.PurchasePlan;
import com.amz.model.Supplier;
import com.amz.model.PurchaseOrder;
import com.amz.model.PurchaseOrderItem;
import com.amz.model.SupplierProduct;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.PurchasePlanService;
import com.amz.service.ProcurementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 采购计划服务实现。
 * <p>
 * 补货引擎输出 → 自动生成采购计划 → 审批 → 转为采购订单。
 */
@Slf4j
@Service
public class PurchasePlanServiceImpl implements PurchasePlanService {

    @Autowired
    private PurchasePlanMapper purchasePlanMapper;

    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;

    @Autowired
    private PurchaseOrderItemMapper purchaseOrderItemMapper;

    @Autowired
    private SupplierProductMapper supplierProductMapper;

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private ProcurementService procurementService;

    @Override
    public PurchasePlan createPlan(PurchasePlan plan) {
        if (plan == null || plan.getShopId() == null || plan.getSku() == null || plan.getPlannedQty() == null) {
            throw new AttrIsNullException("店铺ID、SKU和计划数量不能为空");
        }
        requireShopAccess(plan.getShopId());
        validateSupplierReference(plan.getSupplierId(), plan.getShopId());
        plan.setPlanNo(BizNoGenerator.next("PL"));
        if (plan.getStatus() == null) {
            plan.setStatus("DRAFT");
        }
        if (plan.getSource() == null) {
            plan.setSource("MANUAL");
        }
        if (plan.getUrgency() == null) {
            plan.setUrgency("NORMAL");
        }
        // 自动匹配首选供应商获取预估单价
        if (plan.getUnitPrice() == null || plan.getSupplierId() == null) {
            LambdaQueryWrapper<SupplierProduct> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SupplierProduct::getShopId, plan.getShopId())
                   .eq(SupplierProduct::getSku, plan.getSku())
                   .eq(SupplierProduct::getIsPreferred, 1)
                   .eq(SupplierProduct::getStatus, "ACTIVE");
            SupplierProduct sp = supplierProductMapper.selectOne(wrapper);
            if (sp != null) {
                if (plan.getUnitPrice() == null) plan.setUnitPrice(sp.getSupplyPrice());
                if (plan.getSupplierId() == null) plan.setSupplierId(sp.getSupplierId());
            }
        }
        // 计算总金额
        if (plan.getUnitPrice() != null && plan.getPlannedQty() != null) {
            plan.setTotalAmount(plan.getUnitPrice().multiply(BigDecimal.valueOf(plan.getPlannedQty())));
        }
        purchasePlanMapper.insert(plan);
        log.info("采购计划已创建：planNo={}, sku={}, qty={}", plan.getPlanNo(), plan.getSku(), plan.getPlannedQty());
        return plan;
    }

    @Override
    public PurchasePlan submitForApproval(Long planId) {
        PurchasePlan plan = getPlan(planId);
        if (!"DRAFT".equals(plan.getStatus())) {
            throw new CodeErrorException("仅草稿状态可提交审批，当前状态：" + plan.getStatus());
        }
        plan.setStatus("PENDING_APPROVAL");
        purchasePlanMapper.updateById(plan);
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PurchasePlan approve(Long planId, String operator, boolean approved, String comment) {
        PurchasePlan plan = getPlan(planId);
        if (!"PENDING_APPROVAL".equals(plan.getStatus())) {
            throw new CodeErrorException("仅待审批状态可审批，当前状态：" + plan.getStatus());
        }
        if (approved) {
            plan.setStatus("APPROVED");
            plan.setApprovedBy(operator);
            plan.setApprovedTime(LocalDateTime.now());
        } else {
            plan.setStatus("REJECTED");
            plan.setApprovedBy(operator);
            plan.setApprovedTime(LocalDateTime.now());
            plan.setRemark(comment);
        }
        purchasePlanMapper.updateById(plan);
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> convertToOrder(Long planId) {
        PurchasePlan plan = getPlan(planId);
        if (!"APPROVED".equals(plan.getStatus())) {
            throw new CodeErrorException("仅已审批通过的计划可转为采购订单，当前状态：" + plan.getStatus());
        }

        // 创建采购订单
        PurchaseOrder order = new PurchaseOrder();
        order.setShopId(plan.getShopId());
        order.setSku(plan.getSku());
        order.setQuantity(plan.getPlannedQty());
        order.setUnitPrice(plan.getUnitPrice());
        order.setTotalAmount(plan.getTotalAmount());
        // 注意：不要在此设置 PENDING_APPROVAL——那是计划单据的状态；
        // 采购订单状态机以 DRAFT 起始（createPurchaseOrder 强制），
        // 计划审批已在上方 gate（仅 APPROVED 可转换），订单创建后即为可提交的 DRAFT。
        order.setRemark("由采购计划 " + plan.getPlanNo() + " 转换");
        PurchaseOrder createdOrder = procurementService.createPurchaseOrder(order);

        // 创建采购订单明细
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrderId(createdOrder.getId());
        item.setSku(plan.getSku());
        item.setAsin(plan.getAsin());
        item.setSupplierId(plan.getSupplierId());
        item.setQuantity(plan.getPlannedQty());
        item.setReceivedQuantity(0);
        item.setUnitPrice(plan.getUnitPrice());
        item.setTotalAmount(plan.getTotalAmount());
        purchaseOrderItemMapper.insert(item);

        // 更新计划状态
        plan.setStatus("CONVERTED");
        purchasePlanMapper.updateById(plan);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("planNo", plan.getPlanNo());
        result.put("orderId", createdOrder.getId());
        result.put("orderNo", createdOrder.getOrderNo());
        result.put("status", "CONVERTED");
        return result;
    }

    @Override
    public PageResult<PurchasePlan> listPlans(Long shopId, String status, PageRequest page) {
        requireShopAccess(shopId);
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<PurchasePlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchasePlan::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(PurchasePlan::getStatus, status);
        }
        Long cursorId = req.cursorId();
        if (cursorId != null) {
            wrapper.lt(PurchasePlan::getId, cursorId);
        }
        wrapper.orderByDesc(PurchasePlan::getId)
                .last("LIMIT " + req.probeSize());
        List<PurchasePlan> rows = purchasePlanMapper.selectList(wrapper);
        if (rows.size() > req.size()) {
            log.warn("采购计划列表被截断：shopId={} size={}，调用方需携带 nextCursor 继续翻页",
                    shopId, req.size());
        }
        return PageResult.of(rows, req.size(),
                plan -> PageRequest.encodeCursor(plan.getId()));
    }

    @Override
    public PurchasePlan getPlan(Long id) {
        PurchasePlan plan = purchasePlanMapper.selectById(id);
        if (plan == null) {
            throw new AttrIsNullException("采购计划不存在：id=" + id);
        }
        requireShopAccess(plan.getShopId());
        return plan;
    }

    @Override
    public boolean cancelPlan(Long planId) {
        PurchasePlan plan = getPlan(planId);
        if ("CONVERTED".equals(plan.getStatus()) || "CANCELED".equals(plan.getStatus())) {
            throw new CodeErrorException("已转换或已取消的计划不可取消");
        }
        plan.setStatus("CANCELED");
        purchasePlanMapper.updateById(plan);
        return true;
    }
    private void requireShopAccess(Long shopId) {
        if (shopId == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            log.warn("采购计划店铺越权访问拦截：userId={}, shopId={}", UserContext.getUserId(), shopId);
            throw new CodeErrorException("店铺不存在或无权访问");
        }
    }

    private void validateSupplierReference(Long supplierId, Long shopId) {
        if (supplierId == null) {
            return;
        }
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null || !Objects.equals(supplier.getShopId(), shopId)) {
            throw new CodeErrorException("供应商不存在或与店铺不匹配");
        }
    }
}
