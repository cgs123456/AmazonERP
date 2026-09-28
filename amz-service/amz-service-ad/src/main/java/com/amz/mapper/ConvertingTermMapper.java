package com.amz.mapper;

import com.amz.model.ConvertingTerm;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConvertingTermMapper extends BaseMapper<ConvertingTerm> {

    /**
     * 按 (shop_id, campaign_id, search_term) 原子覆盖窗口统计快照。
     * 人工维护的 status / is_added_to_keyword 不参与覆盖，避免同步任务重置运营状态。
     */
    @Insert("""
            <script>
            INSERT INTO amz_ad_converting_terms
                (shop_id, asin, search_term, campaign_id, total_orders, total_sales, total_cost,
                 avg_acos, first_seen, last_seen, is_added_to_keyword, status)
            VALUES
                <foreach collection="rows" item="row" separator=",">
                    (#{row.shopId}, #{row.asin}, #{row.searchTerm}, #{row.campaignId},
                     #{row.totalOrders}, #{row.totalSales}, #{row.totalCost}, #{row.avgAcos},
                     #{row.firstSeen}, #{row.lastSeen}, #{row.isAddedToKeyword}, #{row.status})
                </foreach>
            ON DUPLICATE KEY UPDATE
                asin = COALESCE(VALUES(asin), asin),
                total_orders = VALUES(total_orders),
                total_sales = VALUES(total_sales),
                total_cost = VALUES(total_cost),
                avg_acos = VALUES(avg_acos),
                first_seen = VALUES(first_seen),
                last_seen = VALUES(last_seen),
                update_time = NOW()
            </script>
            """)
    int upsertBatch(@Param("rows") List<ConvertingTerm> rows);

    /**
     * 回读原子写入后的真实主键。必须同时限定店铺、活动和搜索词，防止跨租户或跨活动串数据。
     */
    @Select("""
            <script>
            SELECT id, shop_id AS shopId, asin, search_term AS searchTerm, campaign_id AS campaignId,
                   total_orders AS totalOrders, total_sales AS totalSales, total_cost AS totalCost,
                   avg_acos AS avgAcos, first_seen AS firstSeen, last_seen AS lastSeen,
                   is_added_to_keyword AS isAddedToKeyword, status
              FROM amz_ad_converting_terms
             WHERE shop_id = #{shopId}
               AND campaign_id IN
                <foreach collection="campaignIds" item="campaignId" open="(" separator="," close=")">
                    #{campaignId}
                </foreach>
               AND search_term IN
                <foreach collection="searchTerms" item="searchTerm" open="(" separator="," close=")">
                    #{searchTerm}
                </foreach>
            </script>
            """)
    List<ConvertingTerm> selectByKeys(@Param("shopId") Long shopId,
                                      @Param("campaignIds") List<String> campaignIds,
                                      @Param("searchTerms") List<String> searchTerms);
}
