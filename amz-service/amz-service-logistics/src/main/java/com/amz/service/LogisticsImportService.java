package com.amz.service;

import com.amz.dto.ImportReport;
import com.amz.dto.ImportShipmentDTO;
import com.amz.dto.ImportTrackingDTO;

import java.util.List;

/**
 * 物流数据外部导入服务（取数入口 A）。
 * <p>
 * 与入口 B（第三方 API 主动拉取）的分工：
 * <ul>
 *   <li><b>入口 A（本接口）</b>：使用者自行获取数据后推入系统，不依赖任何外部凭证，
 *       也不消耗第三方配额。适合承运商官网未开放 API、或需要人工核对后批量回填的场景。</li>
 *   <li><b>入口 B</b>：由调度主动拉取，适合接入聚合服务后无人值守更新。</li>
 * </ul>
 * 两者<b>只共用落库核心，不共用取数方式</b>，因此可以同时启用而不冲突：
 * 轨迹按 (状态, 时间) 指纹合并，谁先写入都不会覆盖对方的历史轨迹。
 */
public interface LogisticsImportService {

    /**
     * 单次导入的货件行数上限。
     * <p>
     * 上限存在的意义不是性能，而是给出「可诊断的失败」：超限时直接拒绝并提示分批，
     * 好过一次超大事务跑几分钟后因单行错误整体回滚，使用者拿不到任何有效信息。
     */
    int MAX_SHIPMENT_ROWS = 2000;

    /** 单次导入的运单行数上限。运单行内含多个轨迹点，故上限低于货件行 */
    int MAX_TRACKING_ROWS = 1000;

    /**
     * 批量导入货件主单（按货件编号 upsert）。
     * <p>
     * 已存在的货件只补齐本次给出的非空字段，未给出的字段保持原值——
     * 导入常是分批进行的（先导主档、后补 ETA），若用空值覆盖会把先前导入的内容清掉。
     *
     * @param rows   货件行，不可为 null
     * @param shopId 目标店铺，货件一律归属该店铺（不取自请求体）
     * @return 导入报告，含新建/更新计数与行级错误明细
     */
    ImportReport importShipments(List<ImportShipmentDTO> rows, Long shopId);

    /**
     * 批量导入运单轨迹点。
     * <p>
     * 每个运单先按 ({@code shipmentNo}, {@code trackingNo}) 在<b>指定店铺内</b>定位货件，
     * 定位失败则该运单计入未匹配清单并整体跳过，不做部分写入。
     *
     * @param rows   运单行，不可为 null
     * @param shopId 限定店铺，越权的运单号不会命中他店货件
     * @return 导入报告，含写入/跳过轨迹点数与未匹配运单清单
     */
    ImportReport importTracking(List<ImportTrackingDTO> rows, Long shopId);
}
