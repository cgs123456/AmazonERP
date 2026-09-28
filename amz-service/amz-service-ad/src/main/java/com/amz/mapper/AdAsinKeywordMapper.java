package com.amz.mapper;

import com.amz.model.AdAsinKeyword;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdAsinKeywordMapper extends BaseMapper<AdAsinKeyword> {

    /**
     * 按 (shop_id, asin, keyword) 原子写入反查快照，避免 select-then-insert 竞态。
     */
    @Insert("""
            <script>
            INSERT INTO amz_ad_asin_keyword
                (shop_id, asin, keyword, organic_rank, ad_rank, search_volume, relevance_score,
                 is_indexed, last_checked)
            VALUES
                <foreach collection="rows" item="row" separator=",">
                    (#{row.shopId}, #{row.asin}, #{row.keyword}, #{row.organicRank}, #{row.adRank},
                     #{row.searchVolume}, #{row.relevanceScore}, #{row.isIndexed}, #{row.lastChecked})
                </foreach>
            ON DUPLICATE KEY UPDATE
                organic_rank = VALUES(organic_rank),
                ad_rank = VALUES(ad_rank),
                search_volume = VALUES(search_volume),
                relevance_score = VALUES(relevance_score),
                is_indexed = VALUES(is_indexed),
                last_checked = VALUES(last_checked),
                update_time = NOW()
            </script>
            """)
    int upsertBatch(@Param("rows") List<AdAsinKeyword> rows);

    /**
     * 回读原子写入后的真实主键。必须限定店铺，禁止仅按 ASIN/关键词跨租户读取。
     */
    @Select("""
            <script>
            SELECT id, shop_id AS shopId, asin, keyword, organic_rank AS organicRank,
                   ad_rank AS adRank, search_volume AS searchVolume, relevance_score AS relevanceScore,
                   is_indexed AS isIndexed, last_checked AS lastChecked
              FROM amz_ad_asin_keyword
             WHERE shop_id = #{shopId}
               AND asin IN
                <foreach collection="asins" item="asin" open="(" separator="," close=")">
                    #{asin}
                </foreach>
               AND keyword IN
                <foreach collection="keywords" item="keyword" open="(" separator="," close=")">
                    #{keyword}
                </foreach>
            </script>
            """)
    List<AdAsinKeyword> selectByKeys(@Param("shopId") Long shopId,
                                     @Param("asins") List<String> asins,
                                     @Param("keywords") List<String> keywords);
}
