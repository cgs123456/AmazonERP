package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    /**
     * 取数来源偏好：IMPORT（仅走外部导入）/ API（仅走第三方拉取）/ AUTO（默认，交给调度择优）。
     * <p>
     * 两个入口并存时，调度只对偏好非 IMPORT 且处于非终态的货件发起 API 拉取，
     * 避免对已经用爬取数据维护的货件重复消耗第三方配额。
     */
    private String dataSource;

    /**
     * 最近一次轨迹取数时间。
     * <p>
     * 两条入口（外部导入 / API 拉取）在成功定位到货件并处理后都会刷新该字段，
     * 包含「查询成功但暂无新轨迹」的情形——它代表的是「我们刚确认过这个货件的状态」，
     * 而非「有新数据」。
     * <p>
     * 两个用途：① 定时同步按此字段升序轮换，保证每轮限量拉取时不会固定饿死尾部货件；
     * ② 看板展示数据新鲜度，使用者据此判断自动同步是否在正常工作。
     * <p>
     * 不能复用 {@code update_time}：轨迹落库只在货件整体状态变化时才更新货件行，
     * 仅新增轨迹点时该行不变，时间戳也随之停滞。
     */
    private LocalDateTime lastTrackTime;

    /**
     * 货件创建时间（数据库已有该列，此处在实体上补映射）。
     * <p>
     * 看板的趋势与时效统计需要按时间窗过滤与分桶，此前实体未映射该列，
     * 导致任何「近 N 天」的聚合都只能全表捞出后在内存里筛。
     * <p>
     * {@code updateStrategy = NEVER} 是必须的：该字段读出来后在更新时会被回写，
     * 而本表 {@code update_time} 依赖 MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP}，
     * 实体若把旧值显式写回，会让「ON UPDATE 自动刷新」失效（显式赋值优先于自动刷新）。
     * 创建时间本身也不应被更新，禁掉更新写入可一并规避这两处问题。
     */
    @TableField(value = "create_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;
}
