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

    /** 是否成功定位到货件。false 表示该运单在系统内无对应货件，需由调用方回吐给用户补主单 */
    private boolean matched;

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

    public static IngestOutcome matched(Long shipmentId) {
        IngestOutcome outcome = new IngestOutcome();
        outcome.setMatched(true);
        outcome.setShipmentId(shipmentId);
        return outcome;
    }
}
