package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.PurchasePlanMapper;
import com.amz.mapper.PurchaseOrderItemMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.PurchasePlan;
import com.amz.model.PurchaseOrder;
import com.amz.model.PurchaseOrderItem;
import com.amz.model.SupplierProduct;
import com.amz.service.PurchasePlanService;
import com.amz.service.ProcurementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

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
    private ProcurementService procurementService;

    @Override
    public PurchasePlan createPlan(PurchasePlan plan) {
        if (plan.getShopId() == null || plan.getSku() == null || plan.getPlannedQty() == null) {
            throw new AttrIsNullException("店铺ID、SKU和计划数量不能为空");
        }
        plan.setPlanNo("PL" + System.currentTimeMillis());
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
            throw new IllegalStateException("仅草稿状态可提交审批，当前状态：" + plan.getStatus());
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
            throw new IllegalStateException("仅待审批状态可审批，当前状态：" + plan.getStatus());
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
            throw new IllegalStateException("仅已审批通过的计划可转为采购订单，当前状态：" + plan.getStatus());
        }

        // 创建采购订单
        PurchaseOrder order = new PurchaseOrder();
        order.setShopId(plan.getShopId());
        order.setSku(plan.getSku());
        order.setQuantity(plan.getPlannedQty());
        order.setUnitPrice(plan.getUnitPrice());
        order.setTotalAmount(plan.getTotalAmount());
        order.setStatus("PENDING_APPROVAL");
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
    public List<PurchasePlan> listPlans(Long shopId, String status) {
        LambdaQueryWrapper<PurchasePlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchasePlan::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(PurchasePlan::getStatus, status);
        }
        wrapper.orderByDesc(PurchasePlan::getId);
        return purchasePlanMapper.selectList(wrapper);
    }

    @Override
    public PurchasePlan getPlan(Long id) {
        PurchasePlan plan = purchasePlanMapper.selectById(id);
        if (plan == null) {
            throw new AttrIsNullException("采购计划不存在：id=" + id);
        }
        return plan;
    }

    @Override
    public boolean cancelPlan(Long planId) {
        PurchasePlan plan = getPlan(planId);
        if ("CONVERTED".equals(plan.getStatus()) || "CANCELED".equals(plan.getStatus())) {
            throw new IllegalStateException("已转换或已取消的计划不可取消");
        }
        plan.setStatus("CANCELED");
        purchasePlanMapper.updateById(plan);
        return true;
    }
}
