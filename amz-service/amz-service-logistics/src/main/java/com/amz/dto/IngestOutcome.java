package com.amz.dto;

import lombok.Data;

/**
 * 单个运单/货件的轨迹落库结果。
 * <p>
 * 由 {@code TrackingIngestService} 返回，导入接口与 API 拉取调度共用同一结构，
 * 保证两条入口对外表现一致（可比较、可统计、可回吐未匹配项）。
 */
@Data
public class IngestOutcome {

    /** 是否成功定位到货件。false 表示未匹配或因标识歧义拒绝写入 */
    private boolean matched;

    /**
     * 是否因外部标识命中多条货件而无法安全落库。
     * <p>
     * 运单号没有唯一约束；命中多条时若继续取第一条，会把甲货件的轨迹写进乙货件。
     * 该状态必须与普通「未匹配」区分，调用方才能提示数据治理而不是让用户补录。
     */
    private boolean ambiguous;

    /** 歧义字段：shipmentNo 或 trackingNo；仅在 ambiguous=true 时有值 */
    private String conflictField;

    /** 定位到的货件 ID */
    private Long shipmentId;

    /** 本次真正写入的轨迹点数 */
    private int accepted;

    /** 因 (状态, 时间) 已存在而跳过的轨迹点数 —— 幂等去重的直接体现 */
    private int skipped;

    /** 货件整体状态是否因本次写入而变化 */
    private boolean statusChanged;

    /** 货件写入后的最新状态，供调用方回显 */
    private String shipmentStatus;

    public static IngestOutcome unmatched() {
        IngestOutcome outcome = new IngestOutcome();
        outcome.setMatched(false);
        return outcome;
    }

    /** 命中多条候选时返回歧义结果；调用方不得把它当作普通未匹配静默处理。 */
    public static IngestOutcome ambiguous(String conflictField) {
        IngestOutcome outcome = new IngestOutcome();
        outcome.setMatched(false);
        outcome.setAmbiguous(true);
        outcome.setConflictField(conflictField);
        return outcome;
    }

    public static IngestOutcome matched(Long shipmentId) {
        IngestOutcome outcome = new IngestOutcome();
        outcome.setMatched(true);
        outcome.setShipmentId(shipmentId);
        return outcome;
    }
}
