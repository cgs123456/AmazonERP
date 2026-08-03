package com.amz.service;

import com.amz.model.PurchasePlan;
import com.amz.model.PurchaseOrderItem;

import java.util.List;
import java.util.Map;

/**
 * 采购计划服务接口。
 */
public interface PurchasePlanService {

    /** 创建采购计划（手动或自动） */
    PurchasePlan createPlan(PurchasePlan plan);

    /** 提交审批（DRAFT → PENDING_APPROVAL） */
    PurchasePlan submitForApproval(Long planId);

    /** 审批通过/拒绝 */
    PurchasePlan approve(Long planId, String operator, boolean approved, String comment);

    /** 将采购计划转为采购订单 */
    Map<String, Object> convertToOrder(Long planId);

    /** 查询采购计划列表 */
    List<PurchasePlan> listPlans(Long shopId, String status);

    /** 获取采购计划详情 */
    PurchasePlan getPlan(Long id);

    /** 取消采购计划 */
    boolean cancelPlan(Long planId);
}
