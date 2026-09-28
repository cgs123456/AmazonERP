package com.amz.preflight;

/**
 * 连通性自检的单个阶段结果（不可变）。
 *
 * <p><b>脱敏约束：</b>{@code detail} 只允许放结构化诊断（状态码、条数、字段名），
 * 严禁出现 access_token、refresh_token、client_secret、RDT 或买家 PII。
 * 失败文本统一过 {@code ErrorSummary.redact}。
 *
 * @param name            阶段名，取自 {@link PreflightReport} 的 STAGE_* 常量
 * @param status          结论；null 会 fail-closed 归一为 {@link PreflightStageStatus#FAIL}
 * @param durationMs      该阶段耗时；SKIP 为 0（未执行，不伪造耗时）
 * @param errorCode       失败码；PASS/SKIP 为 null
 * @param platformStatus  平台 HTTP 状态码；本地失败/无平台响应时为 null
 * @param remediation     面向运维的下一步动作（人话，不是异常类名）
 * @param detail          结构化诊断摘要（已脱敏）
 */
public record PreflightStage(String name, PreflightStageStatus status, long durationMs,
                             String errorCode, Integer platformStatus, String remediation, String detail) {

    public PreflightStage {
        name = name == null || name.isBlank() ? "UNKNOWN" : name;
        status = status == null ? PreflightStageStatus.FAIL : status;
        durationMs = Math.max(0L, durationMs);
        detail = detail == null ? "" : detail;
    }

    public static PreflightStage pass(String name, long durationMs, String detail) {
        return new PreflightStage(name, PreflightStageStatus.PASS, durationMs, null, null, null, detail);
    }

    public static PreflightStage fail(String name, long durationMs, String errorCode,
                                      Integer platformStatus, String remediation, String detail) {
        return new PreflightStage(name, PreflightStageStatus.FAIL, durationMs, errorCode, platformStatus,
                remediation, detail);
    }

    /** 未执行阶段：remediation 必须说明「为什么没跑」，而不是留空让人猜。 */
    public static PreflightStage skip(String name, String remediation) {
        return new PreflightStage(name, PreflightStageStatus.SKIP, 0L, null, null, remediation, "");
    }

    public boolean passed() {
        return status == PreflightStageStatus.PASS;
    }
}
