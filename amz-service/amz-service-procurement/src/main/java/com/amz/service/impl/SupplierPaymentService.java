package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.SupplierPaymentMapper;
import com.amz.model.PurchaseOrder;
import com.amz.model.SupplierPayment;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 供应商付款服务（三单匹配闭环，P2.2）。
 * <p>
 * <b>三单匹配</b>：采购单 + 收货证据 + 付款单。三者齐备才允许付款：
 * <ol>
 *   <li><b>PO 单</b>：存在、归属当前店铺、状态可付款；</li>
 *   <li><b>收货证据</b>：PO 状态 ∈ RECEIVED / COMPLETED / QC_PASSED——
 *       「货到了才付钱」；DRAFT/SUBMITTED/PRODUCING/SHIPPED 一律拒绝
 *       （预付款场景本仓不支持，需要时另行立项）；</li>
 *   <li><b>金额容差</b>：付款金额 vs PO totalAmount 偏差超过容差（默认 1%，可配）
 *       即拒绝，点名三方金额——付错钱是资金事故，静默放过等于没做匹配。</li>
 * </ol>
 * 付款成功把 PO 状态推进为 {@code PAID}：该枚举值自 V1 存在但全仓零写入点，
 * 本服务是它的唯一写入来源。同一 PO 只允许一张有效付款单（防重复付款）。
 */
@Slf4j
@Service
public class SupplierPaymentService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 付款金额相对 PO 金额的允许偏差（1%）。 */
    @Value("${amz.procurement.payment.amount-tolerance:0.01}")
    private java.math.BigDecimal amountTolerance = new java.math.BigDecimal("0.01");

    /** 「货已收」的 PO 状态集合（收货证据）。 */
    private static final Set<String> RECEIPT_EVIDENCE =
            Set.of("RECEIVED", "COMPLETED", "QC_PASSED");

    @Autowired
    private SupplierPaymentMapper paymentMapper;

    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;

    /**
     * 付款（三单匹配通过后执行）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SupplierPayment pay(Long purchaseOrderId, BigDecimal amount, String method, String remark) {
        if (purchaseOrderId == null) {
            throw new CodeErrorException("采购单 ID 不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CodeErrorException("付款金额必须大于 0");
        }
        PurchaseOrder order = purchaseOrderMapper.selectById(purchaseOrderId);
        if (order == null) {
            throw new CodeErrorException("采购单不存在：id=" + purchaseOrderId);
        }
        if (!com.amz.context.UserContext.isShopAllowed(order.getShopId())) {
            throw new CodeErrorException("采购单不属于当前账号可操作的店铺");
        }

        // ① 收货证据：货到了才付钱
        String poStatus = order.getStatus();
        if (!RECEIPT_EVIDENCE.contains(poStatus)) {
            throw new CodeErrorException("采购单当前状态为「" + poStatus
                    + "」，只有已收货（RECEIVED/COMPLETED/QC_PASSED）的采购单可付款");
        }

        // ② 防重复付款：同一 PO 只允许一张有效付款单
        Long existing = paymentMapper.selectCount(new LambdaQueryWrapper<SupplierPayment>()
                .eq(SupplierPayment::getPurchaseOrderId, purchaseOrderId)
                .eq(SupplierPayment::getStatus, SupplierPayment.STATUS_PAID));
        if (existing != null && existing > 0) {
            throw new CodeErrorException("该采购单已存在有效付款单，不可重复付款");
        }

        // ③ 金额容差匹配：偏差超容差点名三方金额拒绝
        BigDecimal poAmount = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
        if (poAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal deviation = amount.subtract(poAmount).abs()
                    .divide(poAmount, 4, java.math.RoundingMode.HALF_UP);
            if (deviation.compareTo(amountTolerance) > 0) {
                throw new CodeErrorException("付款金额与采购单金额偏差超容差：PO 金额=" + poAmount
                        + "，付款金额=" + amount + "，容差=" + amountTolerance.multiply(poAmount)
                        + "。请核对后重试或调整容差配置");
            }
        }

        SupplierPayment payment = new SupplierPayment();
        payment.setShopId(order.getShopId());
        payment.setPaymentNo(generateNo());
        payment.setPurchaseOrderId(purchaseOrderId);
        payment.setPurchaseNo(order.getOrderNo());
        payment.setAmount(amount);
        // PO 无币种列（V1）：付款单币种默认 CNY（1688 采购为人民币场景）
        payment.setCurrency("CNY");
        payment.setMethod(StringUtils.hasText(method) ? method.trim() : "OFFLINE");
        payment.setStatus(SupplierPayment.STATUS_PAID);
        payment.setPoAmountSnapshot(poAmount);
        payment.setOperatorId(UserContext.getUserId() == null ? null : UserContext.getUserId().longValue());
        payment.setRemark(remark);
        paymentMapper.insert(payment);

        // PO 状态推进为 PAID（该枚举值的唯一写入来源）
        order.setStatus("PAID");
        purchaseOrderMapper.updateById(order);
        log.info("供应商付款完成 paymentNo={} poNo={} amount={} poAmount={}",
                payment.getPaymentNo(), order.getOrderNo(), amount, poAmount);
        return payment;
    }

    /**
     * 取消付款（仅管理性纠错用）：PO 状态回退到 RECEIVED。
     * 已生成凭证的 PO 不在此处理（凭证由 finance 域维护）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SupplierPayment cancel(Long paymentId, String reason) {
        SupplierPayment payment = paymentMapper.selectById(paymentId);
        if (payment == null) {
            throw new CodeErrorException("付款单不存在：id=" + paymentId);
        }
        if (!com.amz.context.UserContext.isShopAllowed(payment.getShopId())) {
            throw new CodeErrorException("付款单不属于当前账号可操作的店铺");
        }
        if (!SupplierPayment.STATUS_PAID.equals(payment.getStatus())) {
            throw new CodeErrorException("付款单当前状态为 " + payment.getStatus() + "，不可取消");
        }
        payment.setStatus(SupplierPayment.STATUS_CANCELLED);
        if (StringUtils.hasText(reason)) {
            payment.setRemark((payment.getRemark() == null ? "" : payment.getRemark() + "；") + "取消原因：" + reason);
        }
        paymentMapper.updateById(payment);
        PurchaseOrder order = purchaseOrderMapper.selectById(payment.getPurchaseOrderId());
        if (order != null && "PAID".equals(order.getStatus())) {
            order.setStatus("RECEIVED");
            purchaseOrderMapper.updateById(order);
        }
        log.info("付款取消 paymentNo={} reason={}", payment.getPaymentNo(), reason);
        return payment;
    }

    /**
     * 付款单列表（按店铺）。
     */
    public List<SupplierPayment> list(Long shopId) {
        if (!com.amz.context.UserContext.isShopAllowed(shopId)) {
            throw new CodeErrorException("付款单不属于当前账号可操作的店铺");
        }
        return paymentMapper.selectList(new LambdaQueryWrapper<SupplierPayment>()
                .eq(SupplierPayment::getShopId, shopId)
                .orderByDesc(SupplierPayment::getId));
    }

    private String generateNo() {
        return "PAY" + LocalDateTime.now().format(NO_FMT)
                + ThreadLocalRandom.current().nextInt(100, 1000);
    }
}
