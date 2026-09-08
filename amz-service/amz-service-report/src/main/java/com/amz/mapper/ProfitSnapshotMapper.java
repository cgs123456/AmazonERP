package com.amz.mapper;

import com.amz.model.ProfitSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ProfitSnapshotMapper extends BaseMapper<ProfitSnapshot> {

    /**
     * 按 SKU 聚合快照（B2：profitSummary 不再全量拉回内存分组）。
     * <p>
     * 返回元素：{sku, asin（组内 MAX）, sales, netProfit, snapshotCount}，
     * 按 netProfit 降序（与原内存排序一致）。
     */
    @Select("<script>SELECT sku AS sku, MAX(asin) AS asin, "
            + "SUM(sales_amount) AS sales, SUM(net_profit) AS netProfit, COUNT(*) AS snapshotCount "
            + "FROM amz_profit_snapshot WHERE shop_id = #{shopId} AND sku IS NOT NULL "
            + "<if test='startTime != null'>AND stat_time &gt;= #{startTime}</if>"
            + "<if test='endTime != null'>AND stat_time &lt;= #{endTime}</if>"
            + "GROUP BY sku ORDER BY netProfit DESC</script>")
    List<Map<String, Object>> sumBySku(@Param("shopId") Long shopId,
                                      @Param("startTime") String startTime,
                                      @Param("endTime") String endTime);
}
