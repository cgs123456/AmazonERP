package com.amz.preflight;

/**
 * 连通性自检单个阶段的结论。
 *
 * <p>三种取值是<b>封闭集合</b>，不允许新增第四种模糊状态：
 * <ul>
 *   <li>{@link #PASS}——该阶段实测通过。</li>
 *   <li>{@link #FAIL}——该阶段实测失败，报告必须给出 errorCode 与可操作 remediation。</li>
 *   <li>{@link #SKIP}——该阶段<b>没有执行</b>（前置阶段失败，或进程未装配对应 Bean）。
 *       SKIP <b>不等于</b>通过：{@code PreflightReport.ready()} 要求全部阶段为 PASS，
 *       任何 SKIP 都会使 ready=false，避免把「没跑」读成「没问题」。</li>
 * </ul>
 */
public enum PreflightStageStatus {

    PASS,

    FAIL,

    SKIP
}
