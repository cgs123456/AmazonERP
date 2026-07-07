package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 采购订单实体。
 * <p>
 * 状态流转（参考 1688 采购闭环）：
 * <pre>
 * DRAFT(草稿) → SUBMITTED(已提交1688) → PAID(已付款) → PRODUCING(生产中)
 *   → SHIPPED(已发货) → QC_PENDING(待质检) → QC_PASSED(质检通过)
 *   → RECEIVED(已入库) → COMPLETED(已完成)
 * </pre>
 * 异常分支：QC_FAILED(质检不合格) → 返工/退款；CANCELED(已取消)
 */
@Data
@TableName("amz_purchase_order")
public class PurchaseOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 采购单号（业务唯一） */
    private String orderNo;

    /** 所属店铺 ID */
    private Long shopId;

    /** 1688 供应商 offerId */
    private String supplierOfferId;

    /** 供应商名称 */
    private String supplierName;

    /** 采购商品 SKU */
    private String sku;

    /** 采购数量 */
    private Integer quantity;

    /** 采购单价（含税，CNY） */
    private BigDecimal unitPrice;

    /** 总金额（CNY） */
    private BigDecimal totalAmount;

    /** 状态：DRAFT/SUBMITTED/PAID/PRODUCING/SHIPPED/QC_PENDING/QC_PASSED/RECEIVED/COMPLETED/QC_FAILED/CANCELED */
    private String status;

    /** 1688 平台订单号（下单成功后回填） */
    private String alibabaOrderNo;

    /** 预计交期 */
    private String expectedDeliveryDate;

    /** 物流单号（供应商发货后回填） */
    private String trackingNo;

    /** 备注 */
    private String remark;
}
