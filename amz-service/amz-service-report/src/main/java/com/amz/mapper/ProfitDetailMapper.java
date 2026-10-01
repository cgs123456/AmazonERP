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
     * 口径与逐行实现严格一致：
     * <ul>
     *   <li>{@code rowCount} 就是旧的 {@code group.size()}（行数）。注意表上没有
     *       (shop_id, amazon_order_id, asin) 唯一键，同一订单同一 ASIN 可以有多行，
     *       所以它<b>不等于</b>订单数；服务输出里的 {@code orderCount}/{@code totalOrders}
     *       是历史字段名，本次迁移刻意保持数值不变，改口径会同时改掉报表数字。</li>
     *   <li>{@code COALESCE(col,0)} 对应逐行的 {@code filter(Objects::nonNull)}</li>
     *   <li>费用合计沿用旧的四个分项（fba + 佣金 + 可变结算费 + 仓储），
     *       不含 head-freight/VAT/other，保持与原实现相同的（偏窄的）口径</li>
     * </ul>
     * 等价性在 MySQL 8.4 上对含 NULL 分项、重复订单行、跨店铺行与跨年份行的 fixture 实跑比对确认。
     */
    @Select("<script>SELECT asin AS asin, COUNT(1) AS rowCount, "
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
}
