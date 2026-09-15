package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 外部导入的货件主单行。
 * <p>
 * <b>为什么需要这个导入项：</b>轨迹点是挂在货件上的，若系统内还没有对应货件，
 * 光导入轨迹会全部落成「未匹配」而无法展示。因此导入侧提供两步能力——
 * 先用本 DTO 补建/补齐货件主单，再导入轨迹点。
 * <p>
 * {@code shipmentNo} 为必填且作为<b>幂等自然键</b>：同一份文件重复导入不会产生重复货件，
 * 只会按字段增量补齐。这与「轨迹点用状态指纹去重」共同构成双入口的幂等基础。
 * <p>
 * 注意：{@code shopId} 刻意不在此 DTO 内。店铺归属一律取自请求上下文（接口入参），
 * 若允许请求体自带 shopId，就等于把租户选择权交给调用方，可跨店写数据。
 */
@Data
public class ImportShipmentDTO {

    /** 货件编号（必填，业务唯一，幂等自然键） */
    private String shipmentNo;

    /** Amazon FBA shipmentId */
    private String fbaShipmentId;

    /** 物流方式：SEA / AIR / EXPRESS / TRUCK */
    private String shippingMethod;

    /** 承运商：COSCO / Maersk / DHL / FedEx 等 */
    private String carrier;

    /** 主运单号 —— 轨迹导入按此号匹配货件时的依据 */
    private String masterTrackingNo;

    /** 起运港 */
    private String originPort;

    /** 目的港 / FBA 仓库代码 */
    private String destinationPort;

    /** 目的 FBA 仓库地址 */
    private String fbaWarehouseAddress;

    /** 箱数 */
    private Integer boxCount;

    /** 重量（kg） */
    private BigDecimal weight;

    /** 运费（USD） */
    private BigDecimal freightCost;

    /** 预计到港日期（yyyy-MM-dd）—— 延误判定的唯一依据，导入时建议尽量填全 */
    private String eta;

    /**
     * 货件状态，可选。必须是系统内部货件状态之一：
     * CREATED / IN_TRANSIT / CUSTOMS / DELIVERED / RECEIVED / CLOSED / DELAYED / EXCEPTION。
     * <p>
     * 刻意不做状态文本映射：货件状态（CUSTOMS/DELIVERED/RECEIVED，含 FBA 入库环节）
     * 与轨迹点状态（DEPARTED/ARRIVED/OUT_FOR_DELIVERY，承运商视角）是两套口径，
     * 混用会把「到港」误判成「已入库」。承运商文本请走轨迹导入，由轨迹映射器统一转换。
     * 不填时：新建货件默认 CREATED，已存在货件保持原状态。
     */
    private String status;

    /**
     * 取数来源偏好，可选：IMPORT / API / AUTO。
     * <p>
     * 不填默认 IMPORT —— 既然是由导入维护的货件，调度就不该再对它发起第三方拉取（省配额）。
     * 该值仅影响调度是否主动拉取，两条入口写入轨迹的能力本身不受限制。
     */
    private String dataSource;
}
