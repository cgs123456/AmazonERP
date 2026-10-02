package com.amz.service;

import com.amz.model.PurchaseApproval;
import com.amz.model.PurchasePlan;
import com.amz.model.PurchaseOrderItem;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
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

    /** 审批通过/拒绝（同事务写一条 amz_purchase_approval 留痕） */
    PurchasePlan approve(Long planId, String operator, boolean approved, String comment);

    /**
     * 查询某采购计划的审批留痕，按时间倒序。
     * 归属校验按 plan.shopId，与 approve/convert 走同一条链。
     */
    List<PurchaseApproval> listPlanApprovals(Long planId);

    /** 将采购计划转为采购订单 */
    Map<String, Object> convertToOrder(Long planId);
    /** 查询采购计划列表（游标分页，显式返回截断状态） */
    PageResult<PurchasePlan> listPlans(Long shopId, String status, PageRequest page);

    /** 获取采购计划详情 */
    PurchasePlan getPlan(Long id);

    /** 取消采购计划 */
    boolean cancelPlan(Long planId);
}
