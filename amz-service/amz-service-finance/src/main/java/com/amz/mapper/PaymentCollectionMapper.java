package com.amz.mapper;

import com.amz.model.PaymentCollection;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
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

    /**
     * 台账批量幂等写：同一 (shop_id, amazon_order_id) 已存在就更新，不存在就插入。
     * <p>
     * 存在动因：旧 {@code rebuild} 先把整店台账读进内存建索引，再逐单 select + insert/updateById；
     * 五万订单就是五万次往返 + 一次全表物化。唯一键 {@code uk_shop_amazon_order} 本来就在表上，
     * 幂等交给数据库一条语句做即可。
     * <p>
     * <b>两条不能写错的规则</b>：
     * <ol>
     *   <li>更新列表里<b>没有 shortfall</b> —— 短款是费用比对（T06）写进来的结论，
     *       重算台账不能把它清零，否则"该索赔的钱"就凭空消失了；</li>
     *   <li>{@code status} 用 {@code IF(shortfall > 0, 'SHORTFALL', new.status)}：
     *       等价于旧 Java 里 {@code applyStatusByShortfall} 的判断
     *       （ODKU 里不带表名的列指<b>已存在行</b>的值，所以 {@code shortfall} 是旧值）。</li>
     * </ol>
     * 语义在隔离实例上对 MySQL 8.0.46 与 8.4 各跑一遍核对过（四种情形：有正短款、短款为 0、
     * 短款为 NULL、全新订单），两版结果逐字段一致。
     * <p>
     * {@code AS new} 行别名需要 MySQL 8.0.19+（更早版本会语法报错，而不是静默算错）。
     */
    @Insert("<script>INSERT INTO amz_payment_collection "
            + "(shop_id, amazon_order_id, currency, receivable, fee_deducted, refunded, reimbursed, "
            + "net_received, deposit_date, status, last_calculated_at) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.shopId}, #{r.amazonOrderId}, #{r.currency}, #{r.receivable}, #{r.feeDeducted}, "
            + "#{r.refunded}, #{r.reimbursed}, #{r.netReceived}, #{r.depositDate}, #{r.status}, "
            + "#{r.lastCalculatedAt})"
            + "</foreach>"
            + " AS new ON DUPLICATE KEY UPDATE "
            + "currency = new.currency, receivable = new.receivable, fee_deducted = new.fee_deducted, "
            + "refunded = new.refunded, reimbursed = new.reimbursed, net_received = new.net_received, "
            + "deposit_date = new.deposit_date, last_calculated_at = new.last_calculated_at, "
            + "status = IF(shortfall IS NOT NULL AND shortfall &gt; 0, 'SHORTFALL', new.status)"
            + "</script>")
    int upsertBatch(@Param("rows") List<PaymentCollection> rows);
}
