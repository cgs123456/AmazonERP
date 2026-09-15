package com.amz.dto;

import lombok.Data;

/**
 * 趋势图单日数据点。
 * <p>
 * 只提供 {@link #created}（创建数）与 {@link #delivered}（送达数）两条线：
 * 前者反映发运节奏，后者反映到货产能，两者走势分离即说明在途积压正在累积。
 * <p>
 * 刻意不提供「当日在途数」——按现有表结构无法回溯历史快照，
 * 用当前状态倒推历史在途量会得到随时间扭曲的曲线（今天的已送达货件在过去某天是在途的），
 * 与其画一条看着合理却错误的线，不如不画。
 */
@Data
public class TrendPointDTO {

    /** 日期（yyyy-MM-dd） */
    private String date;

    /** 当日新建货件数 */
    private int created;

    /** 当日承运商确认送达的货件数 */
    private int delivered;
}
