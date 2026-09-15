package com.amz.dto;

import lombok.Data;

/**
 * 导入的单个轨迹点。
 * <p>
 * {@code status} 与 {@code statusCode} 二选一，两者都给时以 {@code statusCode} 为准：
 * <ul>
 *   <li>{@code status} —— 承运商原始状态文本（如「清关中」「IN TRANSIT」），
 *       交由 {@code TrackingStatusMapper} 统一映射，原始文本会存入 raw_status 供回溯；</li>
 *   <li>{@code statusCode} —— 调用方已自行映射好的内部状态码，要求落在固定集合内，
 *       适合爬虫侧已有稳定字典、不想依赖服务端映射规则的场景。</li>
 * </ul>
 * 无论走哪条，无法识别时都不会丢弃该轨迹点（归入 IN_TRANSIT 并记 warn），
 * 因为轨迹点丢失会直接让看板的时效统计失真。
 */
@Data
public class ImportTrackingEventDTO {

    /** 承运商原始状态文本，如「已开船」「OUT FOR DELIVERY」 */
    private String status;

    /** 已映射的内部状态码：CREATED / DEPARTED / IN_TRANSIT / CUSTOMS_CLEARANCE / ARRIVED / OUT_FOR_DELIVERY / DELIVERED / EXCEPTION */
    private String statusCode;

    /** 事件发生地点 */
    private String location;

    /** 事件描述 */
    private String description;

    /**
     * 事件发生时间（建议 ISO-8601，如 2026-08-18T14:30:00）。
     * <p>
     * 该字段参与去重指纹计算，且决定「哪条轨迹是最新的」，因此<b>强烈建议填写</b>：
     * 缺失时会退化为「状态+地点+描述」判重，同一状态多个地点会被重复计入。
     */
    private String eventTime;

    /** 经度（轨迹可视化） */
    private Double longitude;

    /** 纬度（轨迹可视化） */
    private Double latitude;
}
