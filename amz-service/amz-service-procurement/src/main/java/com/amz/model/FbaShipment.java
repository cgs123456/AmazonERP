package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FBA 货件实体。
 * <p>
 * 覆盖 Send to Amazon 全流程：
 * CREATED → PLANNING → READY_TO_SHIP → SHIPPED → IN_TRANSIT → DELIVERED → CHECKED_IN → RECEIVING → CLOSED
 * 异常：EXCEPTION
 */
@Data
@TableName("amz_fba_shipment")
public class FbaShipment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String shipmentNo;
    private String fbaShipmentId;
    private Long warehouseId;
    private String destinationFbaCode;
    private String destinationAddress;
    private String shippingMethod;
    private String carrier;
    private String masterTrackingNo;
    private Integer boxCount;
    private BigDecimal totalWeight;
    private BigDecimal totalVolume;
    private BigDecimal freightCost;
    private BigDecimal customsCost;
    private BigDecimal taxCost;
    private BigDecimal otherCost;
    private BigDecimal totalCost;
    private String status;
    private LocalDate eta;
    private LocalDate actualArrival;
}
