package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 头程物流单 / FBA 货件实体。
 * <p>
 * 头程物流：国内工厂 → 海外 FBA 仓库（海运/空运/快递）。
 * FBA 货件：在 Amazon Seller Central 创建的入库计划，对应一个 FBA shipmentId。
 * <p>
 * 状态流转：
 * <pre>
 * CREATED(已创建) → IN_TRANSIT(运输中) → CUSTOMS(清关中) → DELIVERED(已送达FBA)
 *   → RECEIVED(FBA已签收入库) → CLOSED(已关闭)
 * 异常：DELAYED(延误) / EXCEPTION(异常)
 * </pre>
 */
@Data
@TableName("amz_shipment")
public class Shipment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 货件编号（业务唯一） */
    private String shipmentNo;

    /** Amazon FBA shipmentId（FBA 货件时填） */
    private String fbaShipmentId;

    /** 所属店铺 ID */
    private Long shopId;

    /** 物流方式：SEA(海运) / AIR(空运) / EXPRESS(快递) / TRUCK(卡车) */
    private String shippingMethod;

    /** 物流承运商：COSCO/Maersk/DHL/FedEx 等 */
    private String carrier;

    /** 主运单号 */
    private String masterTrackingNo;

    /** 起运港口/城市 */
    private String originPort;

    /** 目的港口/城市（FBA 仓库代码） */
    private String destinationPort;

    /** 目的 FBA 仓库地址 */
    private String fbaWarehouseAddress;

    /** 货物箱数 */
    private Integer boxCount;

    /** 货物重量（kg） */
    private BigDecimal weight;

    /** 运费（USD） */
    private BigDecimal freightCost;

    /** 状态：CREATED/IN_TRANSIT/CUSTOMS/DELIVERED/RECEIVED/CLOSED/DELAYED/EXCEPTION */
    private String status;

    /** 预计到港日期 */
    private String eta;
}
