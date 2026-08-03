package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("amz_shipment_routing")
public class ShipmentRouting {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String orderId;
    private String sku;
    private String asin;
    private Integer quantity;
    private Long warehouseId;
    private String warehouseName;
    private String warehouseType;
    private String carrierName;
    private String trackingNo;
    private BigDecimal shippingCost;
    private String selectedReason;
    private LocalDateTime routeTime;
}
