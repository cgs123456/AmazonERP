package com.amz.mapper;

import com.amz.model.ProfitDetail;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface ProfitDetailMapper extends BaseMapper<ProfitDetail> {

    /**
     * 按 ASIN 在 SQL 层聚合利润明细，每个 ASIN 返回一行。
     * <p>
     * 存在动因：旧实现把该店铺（日期区间可选，不传即整店）全部明细行读进 Java 再
     * groupingBy(asin) 求和；明细粒度是 (订单, ASIN, 日期)，十万级行只为了得到
     * ASIN 数量的几个数。聚合下沉后内存占用从 O(明细行数) 降到 O(ASIN 数)。
     * <p>
     * 口径（2026-10-01 与业务确认：报表的「订单数」按去重订单计）：
     * <ul>
     *   <li>{@code orderCount} = 该 ASIN 的 {@code COUNT(DISTINCT amazon_order_id)}。
     *       明细粒度是 (订单, ASIN, 日期) 且表上没有对应唯一键，同一订单同一 ASIN 可以有多行
     *       （多次入账/同日报表重叠），所以旧的 {@code COUNT(1)} 是<b>行数</b>而不是订单数：
     *       实测 7 行 fixture 只有 4 个订单，虚高最高 3 倍。</li>
     *   <li>店铺总数<b>不能</b>把各 ASIN 的 orderCount 相加：一单跨两个 ASIN 会被数两次
     *       （实测 B1=3 + B2=2 相加得 5，而全店去重是 4）。总数走
     *       {@link #countDistinctOrders}。</li>
     *   <li>{@code COALESCE(col,0)} 对应逐行的 {@code filter(Objects::nonNull)}</li>
     *   <li>费用合计沿用旧的四个分项（fba + 佣金 + 可变结算费 + 仓储），
     *       不含 head-freight/VAT/other，保持与原实现相同的（偏窄的）口径</li>
     * </ul>
     * 等价性在 MySQL 8.4 上对含 NULL 分项、重复订单行、跨店铺行与跨年份行的 fixture 实跑比对确认。
     */
    @Select("<script>SELECT asin AS asin, COUNT(DISTINCT amazon_order_id) AS orderCount, "
            + "SUM(COALESCE(product_sales, 0)) AS totalSales, "
            + "SUM(COALESCE(product_cost, 0)) AS totalCost, "
            + "SUM(COALESCE(advertising_cost, 0)) AS totalAdSpend, "
            + "SUM(COALESCE(fba_fees, 0) + COALESCE(referral_fee, 0) "
            + "+ COALESCE(variable_closing_fee, 0) + COALESCE(storage_fee, 0)) AS totalFees, "
            + "SUM(COALESCE(gross_profit, 0)) AS grossProfit, "
            + "SUM(COALESCE(net_profit, 0)) AS netProfit "
            + "FROM amz_profit_detail WHERE shop_id = #{shopId} "
            + "<if test='startDate != null'>AND report_date &gt;= #{startDate}</if>"
            + "<if test='endDate != null'>AND report_date &lt;= #{endDate}</if>"
            + "GROUP BY asin</script>")
    List<Map<String, Object>> sumByAsin(@Param("shopId") Long shopId,
                                        @Param("startDate") LocalDate startDate,
                                        @Param("endDate") LocalDate endDate);

    /**
     * 店铺在该区间内的去重订单数，供报表的 {@code totalOrders} 使用。
     * <p>
     * 单独一条查询而不是把 {@link #sumByAsin} 的 per-ASIN {@code orderCount} 加起来：
     * 一个订单可以同时包含多个 ASIN，相加会把它数多次
     * （实测 7 行 / 4 个订单的 fixture，按 ASIN 相加得 5）。
     * 过滤条件必须与 {@code sumByAsin} 同形（同 shop、同日期口径），否则
     * 明细表与总数会对不上。
     */
    @Select("<script>SELECT COUNT(DISTINCT amazon_order_id) FROM amz_profit_detail"
            + " WHERE shop_id = #{shopId}"
            + " <if test='startDate != null'>AND report_date &gt;= #{startDate}</if>"
            + " <if test='endDate != null'>AND report_date &lt;= #{endDate}</if></script>")
    Long countDistinctOrders(@Param("shopId") Long shopId,
                             @Param("startDate") LocalDate startDate,
                             @Param("endDate") LocalDate endDate);
}
