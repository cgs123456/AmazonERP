package com.amz.service;

import com.amz.model.PurchaseOrder;
import com.amz.model.QualityCheck;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

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
     * <p>
     * keyset 游标分页：采购单持续新增，OFFSET 分页在翻页期间会因新行插入而重复或漏行。
     * 结果通过 {@code truncated} / {@code nextCursor} 暴露截断事实，
     * 审批类操作必须翻完全部页，不能只看第一页。
     *
     * @param page 分页参数；null 表示首页 + 默认页大小
     */
    PageResult<PurchaseOrder> listPurchaseOrders(Long shopId, PageRequest page);

    /**
     * 查询「成本可确认」的采购单（QC_PASSED / RECEIVED / COMPLETED），供财务生成 PROCUREMENT 凭证。
     * <p>
     * 只放这三态：草稿、已取消、仍在 1688 流程中的单尚未形成真实成本，
     * 让它们进入凭证流等于把没发生的采购记进利润，而利润口径会照单全扣。
     *
     * @param page 游标分页；null 表示首页 + 默认页大小
     */
    PageResult<PurchaseOrder> listOrdersForVoucher(Long shopId, PageRequest page);

    /**
     * 提交质检结果，自动判定 PASS/FAIL/CONDITIONAL 并更新采购单状态。
     */
    QualityCheck submitQualityCheck(Long purchaseOrderId, Integer sampleCount,
                                    Integer failedCount, String defectDescription, String inspector);
}
