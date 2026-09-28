package com.amz.mapper;

import com.amz.model.AdKeyword;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 广告关键词 Mapper。
 */
@Mapper
public interface AdKeywordMapper extends BaseMapper<AdKeyword> {

    /**
     * 批量写入首次认领的基准价。
     * <p>
     * 唯一键建立后，重复并发认领只会补齐空基准价，不会覆盖已有基准价；
     * 唯一键尚未建立的存量库仍可能出现重复行，需按迁移手册先完成去重。
     */
    @Insert("""
            <script>
            INSERT INTO amz_ad_keyword
                (campaign_id, shop_id, keyword, match_type, bid, base_bid, state)
            VALUES
                <foreach collection="rows" item="row" separator=",">
                    (#{row.campaignId}, #{row.shopId}, #{row.keyword}, #{row.matchType},
                     #{row.bid}, #{row.baseBid}, #{row.state})
                </foreach>
            ON DUPLICATE KEY UPDATE
                base_bid = COALESCE(base_bid, VALUES(base_bid)),
                update_time = NOW()
            </script>
            """)
    int insertBaseBidRows(@Param("rows") List<AdKeyword> rows);

    /**
     * 批量补齐已有行的空基准价；只更新 base_bid 为空的记录，避免覆盖并发认领结果。
     */
    @Update("""
            <script>
            UPDATE amz_ad_keyword
               SET base_bid = CASE id
                    <foreach collection="rows" item="row">
                        WHEN #{row.id} THEN #{row.baseBid}
                    </foreach>
                    ELSE base_bid
                   END,
                   update_time = NOW()
             WHERE shop_id = #{shopId}
               AND base_bid IS NULL
               AND id IN
                    <foreach collection="rows" item="row" open="(" separator="," close=")">
                        #{row.id}
                    </foreach>
            </script>
            """)
    int updateBaseBidsByIds(@Param("shopId") Long shopId, @Param("rows") List<AdKeyword> rows);
}
