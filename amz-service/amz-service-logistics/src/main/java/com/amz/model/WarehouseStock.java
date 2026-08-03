package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("amz_warehouse_stock")
public class WarehouseStock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private Long warehouseId;
    private String warehouseName;
    private String warehouseType;
    private String sku;
    private String asin;
    private Integer availableQty;
    private Integer reservedQty;
    private Integer inboundQty;
    private Integer transferOutQty;
    private Integer totalQty;
    private BigDecimal unitCost;
    private BigDecimal totalValue;
    private java.time.LocalDate lastInboundDate;
    private Integer daysInStock;
    private LocalDateTime snapshotTime;
}
