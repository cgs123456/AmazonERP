package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 库存调拨单实体。
 */
@Data
@TableName("amz_inventory_transfer")
public class InventoryTransfer implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String transferNo;
    private Long fromWarehouseId;
    private Long toWarehouseId;
    private String asin;
    private String sku;
    private Integer quantity;
    private String carrier;
    private String trackingNo;
    private BigDecimal shippingCost;
    private String status;
    private String remark;
}
