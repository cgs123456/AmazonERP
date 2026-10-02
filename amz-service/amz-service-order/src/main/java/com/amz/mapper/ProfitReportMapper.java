package com.amz.mapper;

import com.amz.model.ProfitReport;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ProfitReportMapper extends BaseMapper<ProfitReport> {

    /**
     * 月度利润汇总（查询 v_profit_summary_by_sku 视图对应逻辑）。
     * <p>行数上限走绑定参数：这张表按 SKU×月分组，月份越多行数越多，没有上限时
     * 一次汇总查询会随运行时长越来越贵，而调用方从「一个数组」里看不出被截断。
     * 最近月份在前，所以截掉的是最老的月份，不是刚发生的那几个月。
     */
    @Select("SELECT shop_id, sku, DATE_FORMAT(stat_date, '%Y-%m') as month, " +
            "SUM(revenue) as total_revenue, SUM(product_cost) as total_cost, " +
            "SUM(net_profit) as total_profit, " +
            "ROUND(SUM(net_profit)/NULLIF(SUM(revenue),0),4) as margin " +
            "FROM amz_profit_report WHERE shop_id=#{shopId} " +
            "GROUP BY shop_id, sku, DATE_FORMAT(stat_date, '%Y-%m') " +
            "ORDER BY month DESC LIMIT #{maxRows}")
    List<Map<String, Object>> selectMonthlySummary(@Param("shopId") Long shopId,
                                                   @Param("maxRows") int maxRows);
}
