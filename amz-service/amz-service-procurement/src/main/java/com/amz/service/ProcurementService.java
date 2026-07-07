package com.amz.service;

import com.amz.model.PurchaseOrder;
import com.amz.model.QualityCheck;

import java.util.List;

/**
 * 采购供应链服务接口。
 */
public interface ProcurementService {

    /**
     * 创建采购单（草稿状态）。
     */
    PurchaseOrder createPurchaseOrder(PurchaseOrder order);

    /**
     * 提交采购单到 1688 平台（DRAFT → SUBMITTED）。
     */
    PurchaseOrder submitTo1688(Long orderId);

    /**
     * 同步 1688 订单状态（轮询供应商发货/物流单号）。
     */
    PurchaseOrder syncOrderStatus(Long orderId);

    /**
     * 取消采购单（调用 1688 closeOrder）。
     */
    boolean cancelPurchaseOrder(Long orderId);

    /**
     * 查询店铺的采购单列表。
     */
    List<PurchaseOrder> listPurchaseOrders(Long shopId);

    /**
     * 提交质检结果，自动判定 PASS/FAIL/CONDITIONAL 并更新采购单状态。
     */
    QualityCheck submitQualityCheck(Long purchaseOrderId, Integer sampleCount,
                                    Integer failedCount, String defectDescription, String inspector);
}
