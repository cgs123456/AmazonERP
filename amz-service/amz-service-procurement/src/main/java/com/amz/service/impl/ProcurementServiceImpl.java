package com.amz.service.impl;

import com.amz.client.Alibaba1688Client;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.QualityCheckMapper;
import com.amz.model.PurchaseOrder;
import com.amz.model.QualityCheck;
import com.amz.service.ProcurementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 采购供应链服务实现。
 * <p>
 * 融合 1688 采购闭环 + 内部质检流程：
 * 下单 → 1688同步 → 到货质检 → 入库。
 */
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
        PurchaseOrder order = mustGet(orderId);
        if (!"DRAFT".equals(order.getStatus())) {
            throw new IllegalStateException("仅草稿状态可提交，当前状态：" + order.getStatus());
        }
        // 调用 1688 下单
        String alibabaOrderNo = alibaba1688Client.createOrder(
                order.getSupplierOfferId(), order.getQuantity(), order.getUnitPrice());
        order.setAlibabaOrderNo(alibabaOrderNo);
        order.setStatus("SUBMITTED");
        purchaseOrderMapper.updateById(order);
        return order;
    }

    @Override
    public PurchaseOrder syncOrderStatus(Long orderId) {
        PurchaseOrder order = mustGet(orderId);
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
        PurchaseOrder order = mustGet(orderId);
        if (order.getAlibabaOrderNo() != null) {
            alibaba1688Client.closeOrder(order.getAlibabaOrderNo());
        }
        order.setStatus("CANCELED");
        purchaseOrderMapper.updateById(order);
        return true;
    }

    @Override
    public List<PurchaseOrder> listPurchaseOrders(Long shopId) {
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseOrder::getShopId, shopId).orderByDesc(PurchaseOrder::getId);
        return purchaseOrderMapper.selectList(wrapper);
    }

    @Override
    public QualityCheck submitQualityCheck(Long purchaseOrderId, Integer sampleCount,
                                           Integer failedCount, String defectDescription, String inspector) {
        PurchaseOrder order = mustGet(purchaseOrderId);
        if (!"QC_PENDING".equals(order.getStatus())) {
            throw new IllegalStateException("仅待质检状态可提交质检，当前状态：" + order.getStatus());
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
}
