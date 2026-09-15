package com.amz.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需人工介入的货件提醒。
 * <p>
 * <b>这张列表就是「人工检索物流信息」的替代品。</b>改造前运营要自己逐个货件点开看轨迹、
 * 比对 ETA 才能发现问题；现在把需要处理的情形主动汇总出来，并按严重程度排序——
 * 使用者的动作从「巡查」变成「按清单处理」。
 * <p>
 * 每类提醒都带 {@link #message}（可直接展示的一句话）与 {@link #actionHint}（建议动作），
 * 因为看板的意义是让人知道下一步做什么，而不只是知道出了什么事。
 */
@Data
public class ShipmentAlertDTO {

    /** 提醒类型：DELAYED / EXCEPTION / ETA_OVERDUE / ETA_APPROACHING / STALE_DATA / MISSING_TRACKING_NO */
    private String type;

    /** 严重程度：HIGH / MEDIUM / LOW，前端据此排序与着色 */
    private String severity;

    private Long shipmentId;

    private String shipmentNo;

    private String carrier;

    private String masterTrackingNo;

    /** 货件当前状态 */
    private String status;

    /** 预计到港日期 */
    private String eta;

    /** 超期天数（ETA 已过时为正数）；不适用时为 null */
    private Integer daysOverdue;

    /** 最近一次轨迹取数时间 */
    private LocalDateTime lastTrackTime;

    /** 可直接展示给使用者的说明文字 */
    private String message;

    /** 建议动作 */
    private String actionHint;
}
