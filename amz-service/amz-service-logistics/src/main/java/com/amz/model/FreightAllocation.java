package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 头程费用分摊明细实体。
 */
@Data
@TableName("amz_freight_allocation")
public class FreightAllocation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private Long shipmentId;
    private String asin;
    private String sku;
    private Integer quantity;
    private BigDecimal weightKg;
    private BigDecimal volumeCbm;
    private BigDecimal freightCost;
    private BigDecimal dutyCost;
    private BigDecimal insuranceCost;
    private BigDecimal otherCost;
    private BigDecimal totalCost;
    private BigDecimal unitCost;
    private String allocationMethod;
}
