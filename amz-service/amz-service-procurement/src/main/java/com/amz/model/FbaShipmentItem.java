package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * FBA 货件明细实体。
 */
@Data
@TableName("amz_fba_shipment_item")
public class FbaShipmentItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fbaShipmentId;
    private String sku;
    private String asin;
    private Integer quantity;
    private Integer receivedQuantity;
    private String batchNo;
    private BigDecimal unitCost;
    private BigDecimal freightAllocation;
    private BigDecimal customsAllocation;
    private BigDecimal totalCost;
}
