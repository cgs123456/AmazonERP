package com.amz.mapper;

import com.amz.model.SettlementDetail;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SettlementDetailMapper extends BaseMapper<SettlementDetail> {

    /**
     * 幂等判定：该业务指纹的结算行是否已入库。
     * 用 COUNT 而非 SELECT *，命中唯一索引且不回传整行。
     */
    @Select("SELECT COUNT(1) FROM amz_settlement_detail WHERE row_key = #{rowKey}")
    int countByRowKey(@Param("rowKey") String rowKey);

    /**
     * 指纹查重（批量）：返回已存在的指纹，供落库前一次性去重，
     * 避免逐行往返数据库（一批结算报表可达数千行）。
     */
    @Select("<script>SELECT row_key FROM amz_settlement_detail WHERE row_key IN "
            + "<foreach collection='rowKeys' item='k' open='(' separator=',' close=')'>#{k}</foreach>"
            + "</script>")
    java.util.List<String> selectExistingRowKeys(@Param("rowKeys") java.util.List<String> rowKeys);

    /**
     * 已入库结算行的店铺列表（去重）——供定时增量同步发现需要刷新的店铺。
     */
    @Select("SELECT DISTINCT shop_id FROM amz_settlement_detail")
    java.util.List<Long> selectDistinctShopIds();
}
