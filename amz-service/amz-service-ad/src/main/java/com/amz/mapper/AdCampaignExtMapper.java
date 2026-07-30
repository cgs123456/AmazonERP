package com.amz.mapper;

import com.amz.model.AdCampaignExt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AdCampaignExtMapper extends BaseMapper<AdCampaignExt> {

    /**
     * 批量更新广告活动状态（单条 SQL）。
     * <p>
     * 修复：原 batchUpdateStatus 循环内 selectById + updateById，N 个 ID 触发 2N 次查询。
     * 现改为单条 UPDATE ... WHERE id IN (...)，一次完成。
     *
     * @return 受影响行数
     */
    @Update("<script>" +
            "UPDATE amz_ad_campaign_ext SET status = #{status}, update_time = NOW() " +
            "WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int batchUpdateStatusByIds(@Param("ids") List<Long> ids, @Param("status") String status);
}
