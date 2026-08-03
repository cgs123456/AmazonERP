package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购计划实体。
 * <p>
 * 来源：补货引擎输出 → 自动生成采购计划（source=AUTO），或人工创建（source=MANUAL）。
 * 流程：DRAFT → PENDING_APPROVAL → APPROVED → CONVERTED（转为采购订单）/ REJECTED / CANCELED
 */
@Data
@TableName("amz_purchase_plan")
public class PurchasePlan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String planNo;
    private Long shopId;
    private String sku;
    private String asin;
    private Integer suggestedQty;
    private Integer plannedQty;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private Long supplierId;
    private String urgency;
    private String source;
    private String replenishmentData;
    private String status;
    private String approvedBy;
    private LocalDateTime approvedTime;
    private String remark;
}
