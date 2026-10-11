package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商付款单（三单匹配闭环，P2.2）。
 * <p>
 * 三单 = 采购单 + 收货证据（PO 状态 ∈ RECEIVED/COMPLETED/QC_PASSED）+ 付款单。
 * 付款成功把 PO 状态推进为 {@code PAID}——该枚举值自 V1 存在但全仓零写入点，
 * 本表是它的唯一写入来源。
 */
@Data
@TableName("amz_supplier_payment")
public class SupplierPayment implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String paymentNo;

    private Long purchaseOrderId;

    private String purchaseNo;

    private BigDecimal amount;

    private String currency;

    /** OFFLINE / ALIBABA / BANK */
    private String method;

    private String status;

    /** 付款时 PO 金额快照（容差核对证据） */
    private BigDecimal poAmountSnapshot;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
