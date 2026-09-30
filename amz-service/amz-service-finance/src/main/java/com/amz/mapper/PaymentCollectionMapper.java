package com.amz.mapper;

import com.amz.model.PaymentCollection;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PaymentCollectionMapper extends BaseMapper<PaymentCollection> {

    /**
     * 回款概览：按币种在 SQL 层聚合，每个币种返回一行。
     * <p>
     * 存在动因：旧实现把整店台账全部读进 Java（即便按 500 行分批，最终仍是全量物化）
     * 只为得到十来个数字；订单数到达十万级时，这是纯粹内存与网络带宽的浪费。
     * 聚合下沉后内存占用从 O(台账行数) 降到 O(币种数)。
     * <p>
     * 口径与原逐行实现严格一致（含 NULL 处理）：
     * <ul>
     *   <li>金额项 {@code COALESCE(...,0)}：NULL 按 0 参与累加，与 {@code nz()} 一致</li>
     *   <li>{@code shortfallAmount} 只累加 {@code SHORTFALL} 状态行，其他状态的短款不计入
     *       （有短款却没有 SHORTFALL 状态属于数据异常，不静默纳入合计）</li>
     *   <li>{@code shortfallKnownRows} 统计 {@code shortfall IS NOT NULL} 的行数：
     *       0 表示「从未做过费用比对」，调用方据此给出「0 不等于没有短款」的警告</li>
     *   <li>按币种分组是为了列示涉及币种（多币种时不可跨币种相加），
     *       <b>不是</b>为了分币种返回合计——合计仍由调用方跨组相加并保持该警告</li>
     * </ul>
     * 状态字面量必须与 {@link PaymentCollection} 的 {@code STATUS_*} 常量一致，
     * 由 PaymentCollectionSummarySqlContractTest 锁住。
     */
    @Select("SELECT currency AS currency, "
            + "COUNT(1) AS orderCount, "
            + "SUM(COALESCE(receivable, 0)) AS receivableTotal, "
            + "SUM(COALESCE(net_received, 0)) AS netReceivedTotal, "
            + "SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS pendingOrders, "
            + "SUM(CASE WHEN status = 'IN_TRANSIT' THEN 1 ELSE 0 END) AS inTransitOrders, "
            + "SUM(CASE WHEN status = 'IN_TRANSIT' THEN COALESCE(net_received, 0) ELSE 0 END) AS inTransitAmount, "
            + "SUM(CASE WHEN status = 'SETTLED' THEN 1 ELSE 0 END) AS settledOrders, "
            + "SUM(CASE WHEN status = 'SETTLED' THEN COALESCE(net_received, 0) ELSE 0 END) AS settledAmount, "
            + "SUM(CASE WHEN status = 'REFUNDED' THEN 1 ELSE 0 END) AS refundedOrders, "
            + "SUM(CASE WHEN status = 'SHORTFALL' THEN 1 ELSE 0 END) AS shortfallOrders, "
            + "SUM(CASE WHEN status = 'SHORTFALL' THEN COALESCE(shortfall, 0) ELSE 0 END) AS shortfallAmount, "
            + "SUM(CASE WHEN shortfall IS NOT NULL THEN 1 ELSE 0 END) AS shortfallKnownRows "
            + "FROM amz_payment_collection WHERE shop_id = #{shopId} GROUP BY currency")
    List<Map<String, Object>> aggregateSummaryByCurrency(@Param("shopId") Long shopId);
}
