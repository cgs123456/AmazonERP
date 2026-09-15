package com.amz.service;

import com.amz.dto.FreightCostBoard;
import com.amz.dto.QuoteBoard;
import com.amz.dto.ReceiptBoard;
import com.amz.dto.TransferBoard;

/**
 * 物流运营子域看板聚合服务。
 * <p>
 * 覆盖比价 / 调拨 / 头程成本 / 签收差异四个子域。
 * 与 {@link LogisticsDashboardService}（头程轨迹看板）分开的原因：
 * 前者围绕「货件 + 轨迹」这一个主体展开，后者要横跨四张互不相关的业务表；
 * 混在一个类里会让依赖越挂越多、聚合口径互相干扰。
 * <p>
 * <b>共同约定：</b>
 * <ul>
 *   <li>一律要求 shopId，不做全库聚合——跨店汇总既无业务意义，也会成为越权读取通道。</li>
 *   <li>采用「按店铺一次取回 + 内存聚合」而不是每个指标一条 SQL：
 *       单店铺数据量级小，且十余个指标下推数据库就是十余次往返，
 *       口径散在 SQL 里也难以复用与测试。量级上升后替换实现即可，对外契约不变。</li>
 *   <li>「无样本」与「值为 0」必须可区分：前者返回 {@code null}，后者才是 0。
 *       0 会被读成「情况良好」，而实际含义可能是「压根没有数据」。</li>
 * </ul>
 */
public interface LogisticsOpsDashboardService {

    /** 报价看板：有效性 / 覆盖面 / 航线竞争度 */
    QuoteBoard quoteBoard(Long shopId);

    /** 调拨看板：状态分布 / 卡单风险 */
    TransferBoard transferBoard(Long shopId);

    /** 头程成本看板：成本构成 / 分摊覆盖率 / 高成本品 */
    FreightCostBoard freightCostBoard(Long shopId);

    /** 签收差异看板：少收多收 / 待处理差额 */
    ReceiptBoard receiptBoard(Long shopId);
}
