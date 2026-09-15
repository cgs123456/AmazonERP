package com.amz.service;

import com.amz.dto.IngestOutcome;
import com.amz.model.TrackingEvent;

import java.util.List;

/**
 * 轨迹落库统一入口。
 * <p>
 * <b>设计要点：两个取数入口必须共用这一层。</b>
 * 物流数据有两条来源路径 —— ① 外部爬取后经导入接口灌入；② 第三方聚合 API 主动拉取 ——
 * 若各自实现落库，会立刻出现两类问题：状态映射规则分叉（同一承运商文本在两处被解成不同状态）、
 * 去重口径不一致（A 链路先删后插会把 B 链路刚写入的历史轨迹抹掉）。
 * 因此导入接口、手动 sync、定时调度三条触发路径全部收敛到本接口。
 */
public interface TrackingIngestService {

    /** 数据来源标记：外部导入 */
    String SOURCE_IMPORT = "IMPORT";

    /** 数据来源标记：第三方 API 拉取 */
    String SOURCE_API = "API";

    /** 数据来源标记：离线模拟 */
    String SOURCE_MOCK = "MOCK";

    /**
     * 幂等写入轨迹点并回写货件状态（不带租户约束，仅限内部已自证归属的调用方使用）。
     * <p>
     * 供「按货件 ID 取出后同步」这类已在上游锁定店铺的场景使用，此处不再重复过滤。
     * 任何以「外部传入的运单号」为入口的路径（用户导入等）必须改用
     * {@link #ingest(String, String, List, String, Long)} 并显式传入 shopId。
     *
     * @param shipmentNo 货件编号，可为 null
     * @param trackingNo 主运单号，可为 null（与 shipmentNo 至少提供一个）
     * @param events     待写入轨迹点，可为空（此时仅返回货件当前状态）
     * @param source     来源标记，取 {@link #SOURCE_IMPORT} / {@link #SOURCE_API} / {@link #SOURCE_MOCK}
     * @return 落库结果，含写入数、跳过数与货件状态变化
     */
    default IngestOutcome ingest(String shipmentNo, String trackingNo, List<TrackingEvent> events, String source) {
        return ingest(shipmentNo, trackingNo, events, source, null);
    }

    /**
     * 幂等写入轨迹点并回写货件状态（带租户约束）。
     * <p>
     * 定位货件时先按货件编号匹配，再退化为按主运单号匹配；两者都未命中则返回未匹配结果，
     * 由调用方汇总后回吐给使用者（用于提示补录货件主单），而非静默丢弃。
     * <p>
     * <b>shopId 为何必须传：</b>货件编号与运单号都由外部输入，若不在匹配阶段限定店铺，
     * 甲店铺用户在导入时填入乙店铺的运单号，轨迹就会被写进乙店铺的货件——
     * 一次越权写入。因此租户过滤下沉到匹配环节，而非只在 Controller 层校验入参。
     *
     * @param shipmentNo 货件编号，可为 null
     * @param trackingNo 主运单号，可为 null（与 shipmentNo 至少提供一个）
     * @param events     待写入轨迹点，可为空（此时仅返回货件当前状态）
     * @param source     来源标记，取 {@link #SOURCE_IMPORT} / {@link #SOURCE_API} / {@link #SOURCE_MOCK}
     * @param shopId     限定店铺；为 null 表示不做租户过滤（内部定时任务等已自证归属的场景）
     * @return 落库结果，含写入数、跳过数与货件状态变化
     */
    IngestOutcome ingest(String shipmentNo, String trackingNo, List<TrackingEvent> events, String source, Long shopId);

    /**
     * 依据 ETA 补判延误：对非终态货件，若预计到港日期已过（含宽限天数）则置为 DELAYED。
     * <p>
     * 刻意与轨迹写入解耦：延误是「时间流逝」导致的，不依赖新轨迹到达，
     * 因此由调度周期性重判，而不是只在收到新事件时算一次。
     *
     * @param graceDays 宽限天数，0 表示 ETA 当天即不判延误
     * @return 本次被置为 DELAYED 的货件数
     */
    int markDelayedShipments(int graceDays);
}
