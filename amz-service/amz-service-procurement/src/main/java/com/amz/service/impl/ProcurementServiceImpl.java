package com.amz.service.impl;

import com.amz.client.Alibaba1688Client;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.QualityCheckMapper;
import com.amz.model.PurchaseOrder;
import com.amz.model.QualityCheck;
import com.amz.service.ProcurementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 采购供应链服务实现。
 * <p>
 * 融合 1688 采购闭环 + 内部质检流程：
 * 下单 → 1688同步 → 到货质检 → 入库。
 */
@Slf4j
@Service
public class ProcurementServiceImpl implements ProcurementService {

    /** 质检合格率阈值：≥95% PASS，<90% FAIL，中间 CONDITIONAL */
    private static final BigDecimal PASS_THRESHOLD = new BigDecimal("95");
    private static final BigDecimal FAIL_THRESHOLD = new BigDecimal("90");

    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;

    @Autowired
    private QualityCheckMapper qualityCheckMapper;

    @Autowired
    private Alibaba1688Client alibaba1688Client;

    @Override
    public PurchaseOrder createPurchaseOrder(PurchaseOrder order) {
        if (order.getQuantity() == null || order.getUnitPrice() == null) {
            throw new AttrIsNullException("采购数量和单价不能为空");
        }
        // 创建即校验店铺授权：防止以任意 shopId 建单绕过后续按 ID 的越权校验
        if (!com.amz.context.UserContext.isShopAllowed(order.getShopId())) {
            throw new IllegalStateException("无权为该店铺创建采购单：shopId=" + order.getShopId());
        }
        // 生成业务单号 + 计算总金额
        order.setOrderNo("PO" + System.currentTimeMillis());
        order.setTotalAmount(order.getUnitPrice()
                .multiply(BigDecimal.valueOf(order.getQuantity())));
        order.setStatus("DRAFT");
        purchaseOrderMapper.insert(order);
        return order;
    }

    @Override
    public PurchaseOrder submitTo1688(Long orderId) {
        PurchaseOrder order = mustGetAuthorized(orderId);
        if (!"DRAFT".equals(order.getStatus())) {
            throw new IllegalStateException("仅草稿状态可提交，当前状态：" + order.getStatus());
        }
        // Outbox-lite 防重复下单：远程调用前先持久化 SUBMITTING 状态。
        // 若进程在"1688 下单成功后、本地落库前"崩溃：
        //  - 旧实现：本地仍是 DRAFT → 重试会再次调用 createOrder 产生重复真实订单；
        //  - 现在：本地为 SUBMITTING → 重试被状态机拒绝，需人工核对 1688 后台后手工推进。
        order.setStatus("SUBMITTING");
        purchaseOrderMapper.updateById(order);

        String alibabaOrderNo;
        try {
            alibabaOrderNo = alibaba1688Client.createOrder(
                    order.getSupplierOfferId(), order.getQuantity(), order.getUnitPrice());
        } catch (Exception e) {
            // 远程失败回滚到 DRAFT 允许重试；若是"成功但响应丢失"的极端场景，
            // 回滚后重试可能产生重复单——由 1688 后台对账兜底（已注释说明的残余风险）
            order.setStatus("DRAFT");
            purchaseOrderMapper.updateById(order);
            throw new IllegalStateException("1688 下单失败，已回滚为草稿：" + e.getMessage(), e);
        }
        order.setAlibabaOrderNo(alibabaOrderNo);
        order.setStatus("SUBMITTED");
        purchaseOrderMapper.updateById(order);
        return order;
    }

    @Override
    public PurchaseOrder syncOrderStatus(Long orderId) {
        PurchaseOrder order = mustGetAuthorized(orderId);
        if (order.getAlibabaOrderNo() == null) {
            throw new IllegalStateException("尚未提交 1688，无法同步状态");
        }
        String status = alibaba1688Client.queryOrderStatus(order.getAlibabaOrderNo());
        switch (status) {
            case "WAIT_SEND":
                order.setStatus("PRODUCING");
                break;
            case "WAIT_RECEIVE":
                order.setStatus("SHIPPED");
                order.setTrackingNo(alibaba1688Client.queryTrackingNo(order.getAlibabaOrderNo()));
                break;
            case "FINISHED":
                order.setStatus("QC_PENDING");
                break;
            default:
                break;
        }
        purchaseOrderMapper.updateById(order);
        return order;
    }

    @Override
    public boolean cancelPurchaseOrder(Long orderId) {
        PurchaseOrder order = mustGetAuthorized(orderId);
        // 状态护栏：已质检通过/已完成等终态不允许取消，避免误关真实 1688 订单
        String status = order.getStatus();
        if ("CANCELED".equals(status) || "QC_PASSED".equals(status)
                || "QC_FAILED".equals(status) || "RECEIVED".equals(status)) {
            throw new IllegalStateException("当前状态不可取消：" + status);
        }
        if (order.getAlibabaOrderNo() != null) {
            // 远程关闭失败时中止本地状态迁移：ERP 显示已取消而 1688 订单仍存续，
            // 会造成后续重复入库/付款风险
            boolean remoteClosed = alibaba1688Client.closeOrder(order.getAlibabaOrderNo());
            if (!remoteClosed) {
                log.warn("1688 关闭订单失败，本地取消中止：orderId={} alibabaOrderNo={}",
                        orderId, order.getAlibabaOrderNo());
                return false;
            }
        }
        order.setStatus("CANCELED");
        purchaseOrderMapper.updateById(order);
        return true;
    }

    @Override
    public List<PurchaseOrder> listPurchaseOrders(Long shopId) {
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseOrder::getShopId, shopId)
                .orderByDesc(PurchaseOrder::getId)
                // 安全上限：防止历史数据累积后全量拉取
                .last("LIMIT 500");
        return purchaseOrderMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityCheck submitQualityCheck(Long purchaseOrderId, Integer sampleCount,
                                           Integer failedCount, String defectDescription, String inspector) {
        PurchaseOrder order = mustGetAuthorized(purchaseOrderId);
        if (!"QC_PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("仅待质检状态可提交质检，当前状态：" + order.getStatus());
        }
        // 输入校验：旧实现 sampleCount=null 直接 NPE、=0 触发除零、failed>sample 得负合格数
        if (sampleCount == null || failedCount == null
                || sampleCount < 1 || failedCount < 0 || failedCount > sampleCount) {
            throw new IllegalArgumentException(
                    "质检数量非法：需满足 1 ≤ failedCount ≤ sampleCount，实际 sample="
                            + sampleCount + " failed=" + failedCount);
        }

        QualityCheck qc = new QualityCheck();
        qc.setPurchaseOrderId(purchaseOrderId);
        qc.setSampleCount(sampleCount);
        qc.setFailedCount(failedCount);
        qc.setPassedCount(sampleCount - failedCount);
        qc.setDefectDescription(defectDescription);
        qc.setInspector(inspector);

        // 计算合格率并判定
        BigDecimal passRate = BigDecimal.valueOf(qc.getPassedCount())
                .divide(BigDecimal.valueOf(sampleCount), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        qc.setPassRate(passRate);

        if (passRate.compareTo(PASS_THRESHOLD) >= 0) {
            qc.setResult("PASS");
            order.setStatus("QC_PASSED");
        } else if (passRate.compareTo(FAIL_THRESHOLD) < 0) {
            qc.setResult("FAIL");
            order.setStatus("QC_FAILED");
        } else {
            qc.setResult("CONDITIONAL");
            // 让步接收，需人工审批，暂置 QC_PASSED
            order.setStatus("QC_PASSED");
        }
        qualityCheckMapper.insert(qc);
        purchaseOrderMapper.updateById(order);
        return qc;
    }

    private PurchaseOrder mustGet(Long orderId) {
        PurchaseOrder order = purchaseOrderMapper.selectById(orderId);
        if (order == null) {
            throw new AttrIsNullException("采购单不存在：id=" + orderId);
        }
        return order;
    }

    /**
     * 加载采购单并做多租户越权校验。
     * submit/sync/cancel/qc 端点仅携带订单 ID、无 shopId 参数，
     * ShopScoped 切面无法按参数名拦截，故在服务层显式校验
     * 当前用户授权店铺是否包含该采购单所属店铺（防跨租户操作他人 PO）。
     */
    private PurchaseOrder mustGetAuthorized(Long orderId) {
        PurchaseOrder order = mustGet(orderId);
        if (!com.amz.context.UserContext.isShopAllowed(order.getShopId())) {
            throw new IllegalStateException(
                    "无权操作该店铺的采购单：orderId=" + orderId + " shopId=" + order.getShopId());
        }
        return order;
    }
}
