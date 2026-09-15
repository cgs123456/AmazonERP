package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 承运商时效表现。
 * <p>
 * <b>为什么按承运商拆：</b>头程延误的归因落点通常就是「换承运商」或「换线路」，
 * 而不是「换仓库」或「换商品」。给出每家承运商的平均时效与延误率，
 * 决策才能落到可执行的选项上；只给一个全店平均值则无法指向任何动作。
 */
@Data
public class CarrierPerformanceDTO {

    /** 承运商名称；数据缺失时由服务层填「未填写」，避免前端出现空白分组 */
    private String carrier;

    /** 该承运商货件总数 */
    private int total;

    /** 在跟踪中的货件数 */
    private int active;

    /** 已确认送达的货件数 */
    private int delivered;

    /** 延误货件数 */
    private int delayed;

    /** 异常货件数 */
    private int exception;

    /** 已送达货件的平均头程时效（天）；无样本时为 null */
    private Double avgTransitDays;

    /** 延误率（延误 ÷ 总数），保留 4 位小数；总数为 0 时为 null */
    private BigDecimal delayedRate;

    /** 异常率（异常 ÷ 总数）；总数为 0 时为 null */
    private BigDecimal exceptionRate;
}
