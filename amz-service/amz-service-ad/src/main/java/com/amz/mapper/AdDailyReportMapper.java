package com.amz.mapper;

import com.amz.model.AdDailyReport;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AdDailyReportMapper extends BaseMapper<AdDailyReport> {

    /**
     * 原子幂等写入日报。唯一键 uk_shop_campaign_date 冲突时覆盖本次同步字段，
     * 避免 select-then-insert/update 在并发或重复回补时产生竞态。
     */
    @Insert("""
            INSERT INTO amz_ad_daily_report
                (shop_id, campaign_id, ad_type, report_date, impressions, clicks, cost, sales, orders,
                 units, acos, roas, cr, ctr, cpc)
            VALUES
                (#{shopId}, #{campaignId}, #{adType}, #{reportDate}, #{impressions}, #{clicks}, #{cost},
                 #{sales}, #{orders}, #{units}, #{acos}, #{roas}, #{cr}, #{ctr}, #{cpc})
            ON DUPLICATE KEY UPDATE
                ad_type = VALUES(ad_type),
                impressions = VALUES(impressions),
                clicks = VALUES(clicks),
                cost = VALUES(cost),
                sales = VALUES(sales),
                orders = VALUES(orders),
                units = VALUES(units),
                acos = VALUES(acos),
                roas = VALUES(roas),
                cr = VALUES(cr),
                ctr = VALUES(ctr),
                cpc = VALUES(cpc),
                update_time = NOW()
            """)
    int upsert(AdDailyReport row);

    /**
     * 按活动聚合报表，并以该活动最小的日报 id 作为稳定 keyset 游标。
     * 日报按 (shop_id, campaign_id, report_date) 唯一，新增某天数据不会改变既有活动的 MIN(id)，
     * 因此翻页期间新日报入库不会造成重复或漏页。
     */
    @Select("""
            <script>
            SELECT MIN(id) AS id,
                   campaign_id AS campaignId,
                   COALESCE(SUM(impressions), 0) AS impressions,
                   COALESCE(SUM(clicks), 0) AS clicks,
                   COALESCE(SUM(cost), 0) AS cost,
                   COALESCE(SUM(sales), 0) AS sales,
                   COALESCE(SUM(orders), 0) AS orders,
                   COALESCE(SUM(units), 0) AS units
            FROM amz_ad_daily_report
            WHERE shop_id = #{shopId}
              AND report_date &gt;= #{start}
              AND report_date &lt;= #{end}
              AND campaign_id IS NOT NULL
              AND campaign_id != ''
            GROUP BY campaign_id
            <if test="cursorId != null">
              HAVING MIN(id) &gt; #{cursorId}
            </if>
            ORDER BY MIN(id) ASC
            LIMIT #{limit}
            </script>
            """)
    List<AdDailyReport> aggregateCampaignReports(@Param("shopId") Long shopId,
                                                  @Param("start") LocalDate start,
                                                  @Param("end") LocalDate end,
                                                  @Param("cursorId") Long cursorId,
                                                  @Param("limit") int limit);

    /**
     * 一次查询返回店铺周期汇总。无数据时 id 为 null，调用方据此返回 null，
     * 不再为了汇总把全部活动行加载进 JVM。
     */
    @Select("""
            SELECT MIN(id) AS id,
                   COALESCE(SUM(impressions), 0) AS impressions,
                   COALESCE(SUM(clicks), 0) AS clicks,
                   COALESCE(SUM(cost), 0) AS cost,
                   COALESCE(SUM(sales), 0) AS sales,
                   COALESCE(SUM(orders), 0) AS orders,
                   COALESCE(SUM(units), 0) AS units
            FROM amz_ad_daily_report
            WHERE shop_id = #{shopId}
              AND report_date &gt;= #{start}
              AND report_date &lt;= #{end}
              AND campaign_id IS NOT NULL
              AND campaign_id != ''
            """)
    AdDailyReport aggregateShopReport(@Param("shopId") Long shopId,
                                      @Param("start") LocalDate start,
                                      @Param("end") LocalDate end);
}
