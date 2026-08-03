package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库存批次实体（先进先出 FIFO）。
 * <p>
 * 每批入库货物分配唯一批次号，记录采购成本+运费+关税分摊。
 * 出库时按入库日期先后自动分配批次，成本随实物流转。
 */
@Data
@TableName("amz_inventory_batch")
public class InventoryBatch implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String batchNo;
    private Long purchaseOrderId;
    private Long inboundOrderId;
    private String sku;
    private String asin;
    private Long warehouseId;
    private Integer quantity;
    private Integer availableQuantity;
    private BigDecimal unitCost;
    private BigDecimal freightCost;
    private BigDecimal customsCost;
    private BigDecimal otherCost;
    private BigDecimal totalCost;
    private LocalDate inboundDate;
    private LocalDate expireDate;
    private String status;
}
