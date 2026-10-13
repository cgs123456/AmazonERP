package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购发票（三单匹配的第三单，A3）。
 *
 * <p>状态机：UNMATCHED（已登记未关联 PO）→ MATCHED（已关联且三方一致）/ CANCELLED（作废）。
 * 刻意没有 PAID——付款是 SupplierPayment 的职责，发票只管「票本身登记与匹配」，
 * 两个域不交叉写状态（否则同一笔业务会有两个地方能改终态，出问题时分不清谁改的）。
 *
 * <p>{@code purchaseOrderId} 可空：供应商常先开票、后对账，允许「先登记、后关联」。
 */
@Data
@TableName("amz_purchase_invoice")
public class PurchaseInvoice implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_UNMATCHED = "UNMATCHED";
    public static final String STATUS_MATCHED = "MATCHED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 供应商发票号（同店铺唯一）。 */
    private String invoiceNo;

    /** 关联采购单 ID（可空：先登记后关联）。 */
    private Long purchaseOrderId;

    /** 开票方（冗余，便于按供应商查）。 */
    private String supplierName;

    /** 发票金额（不含税额）。 */
    private BigDecimal invoiceAmount;

    /** 税额。 */
    private BigDecimal taxAmount;

    /** 开票日期。 */
    private LocalDate invoiceDate;

    private String status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
