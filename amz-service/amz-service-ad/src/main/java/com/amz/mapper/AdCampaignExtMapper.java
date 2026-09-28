package com.amz.mapper;

import com.amz.model.AdCampaignExt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AdCampaignExtMapper extends BaseMapper<AdCampaignExt> {

    /**
     * 按 shop_id 升序 keyset 分页读取有广告活动配置的店铺。
     * <p>使用 keyset 而不是 DISTINCT 全表结果集：店铺数增长时每页固定大小，
     * 不会把整张活动表的结果一次性拉入 JVM。首次查询传 {@link Long#MIN_VALUE}。
     */
    @Select("""
            SELECT DISTINCT shop_id
              FROM amz_ad_campaign_ext
             WHERE shop_id > #{cursor}
             ORDER BY shop_id ASC
             LIMIT #{limit}
            """)
    List<Long> selectShopIdsAfter(@Param("cursor") long cursor, @Param("limit") int limit);

    /**
     * 按主键升序分页读取某店铺的活动类型映射。
     * <p>只选择映射所需列，避免把活动性能指标和描述字段全部加载进内存。
     */
    @Select("""
            SELECT id, campaign_id, ad_type
              FROM amz_ad_campaign_ext
             WHERE shop_id = #{shopId}
               AND id > #{cursor}
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    List<AdCampaignExt> selectAdTypePage(@Param("shopId") Long shopId,
                                         @Param("cursor") long cursor,
                                         @Param("limit") int limit);
    /**
     * 批量更新广告活动状态（单条 SQL，强制带 shop_id 租户条件）。
     *
     * <p>旧实现仅按 id 更新；只要请求参数中混入其他店铺的 id，就会跨店改状态。
     * 租户条件必须下推到 SQL，不能只依赖调用前的归属查询。
     *
     * @return 受影响行数
     */
    @Update("<script>" +
            "UPDATE amz_ad_campaign_ext SET status = #{status}, update_time = NOW() " +
            "WHERE shop_id = #{shopId} AND id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int batchUpdateStatusByIds(@Param("shopId") Long shopId,
                               @Param("ids") List<Long> ids,
                               @Param("status") String status);

    /**
     * 原子写入 Advertising API 返回的活动元数据。
     * <p>
     * 唯一键为 {@code (shop_id, campaign_id)}。更新分支仅覆盖元数据字段，
     * 不触碰由日报同步维护的 impressions/clicks/spend/sales/orders/acos/roas。
     */
    @Insert("""
            INSERT INTO amz_ad_campaign_ext
                (shop_id, campaign_id, campaign_name, ad_type, campaign_type, budget, budget_type,
                 bidding_strategy, status)
            VALUES
                (#{shopId}, #{campaignId}, #{campaignName}, #{adType}, #{campaignType}, #{budget},
                 #{budgetType}, #{biddingStrategy}, #{status})
            ON DUPLICATE KEY UPDATE
                campaign_name = VALUES(campaign_name),
                ad_type = VALUES(ad_type),
                campaign_type = VALUES(campaign_type),
                budget = VALUES(budget),
                budget_type = VALUES(budget_type),
                bidding_strategy = VALUES(bidding_strategy),
                status = VALUES(status),
                update_time = NOW()
            """)
    int upsertMetadata(AdCampaignExt campaign);
}
