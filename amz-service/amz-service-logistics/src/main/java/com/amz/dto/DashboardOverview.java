package com.amz.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物流看板概览。
 * <p>
 * <b>设计取舍：只放「能据以行动」的指标。</b>看板的价值不在于把数字铺满，
 * 而在于让需要处理的事情跳出来。因此除了常规的状态计数与平均时效，
 * 还刻意包含三类「不作为就会被忽略」的指标：
 * <ul>
 *   <li>{@link #etaOverdue} —— ETA 已过却还没被标为延误的货件。
 *       延误标记依赖定时任务，任务未跑或刚过期时会漏标，直接展示可避免依赖任务时序；</li>
 *   <li>{@link #staleShipments} —— 在途却长时间没有取数记录的货件。
 *       这是判断「自动同步是否还活着」的唯一信号：数字不动时，无法区分
 *       「真的没变化」与「同步已静默失效」；</li>
 *   <li>{@link #missingTrackingNo} —— 在途却没有运单号的货件，永远查不到轨迹，
 *       需要人工补录，正是「人工填表」成本的主要来源。</li>
 * </ul>
 */
@Data
public class DashboardOverview {

    /** 全部货件数 */
    private int totalShipments;

    /** 各状态货件数，含全部合法状态（无数据的状态补 0，前端渲染不必做兜底） */
    private Map<String, Integer> statusCounts = new LinkedHashMap<>();

    /** 在跟踪中的货件数（非送达/非入库/非关闭） */
    private int activeShipments;

    /** 已判定延误的货件数 */
    private int delayed;

    /** 出现异常的货件数（扣关/查验/退件等） */
    private int exception;

    /** 7 天内预计到港的货件数 */
    private int arrivingIn7Days;

    /** ETA 已过但状态尚未标为延误的货件数 */
    private int etaOverdue;

    /** 在跟踪中但缺少运单号的货件数（无法自动取数，需人工补录） */
    private int missingTrackingNo;

    /** 在跟踪中但取数时间已超过 {@link #staleThresholdHours} 的货件数 */
    private int staleShipments;

    /** 判定「取数过期」的小时阈值，随结果一起返回，避免前端硬编码造成口径不一致 */
    private int staleThresholdHours;

    /**
     * 平均头程时效（天）：创建 → 承运商确认送达。
     * <p>
     * 仅在统计窗口内有送达事件时才有值，否则为 null（而非 0）——
     * 0 会被误读为「当天送达」，null 才是「暂无数据」的正确表达。
     */
    private Double avgTransitDays;

    /** 平均时效的统计窗口天数，随结果返回以便前端注明口径 */
    private int transitStatWindowDays;

    /** 全店最近一次轨迹取数时间（数据新鲜度） */
    private LocalDateTime lastTrackTime;

    /**
     * 第三方自动取数当前是否可用。
     * <p>
     * 前端据此解释「为什么没有自动更新」：不可用时提示走外部导入，
     * 而不是让使用者看着不动的数据反复点刷新。
     */
    private boolean autoSyncAvailable;

    /** 各取数来源偏好的货件数：IMPORT / API / AUTO */
    private Map<String, Integer> dataSourceCounts = new LinkedHashMap<>();
}
