package com.amz.mapper;

import com.amz.model.CostAllocation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CostAllocationMapper extends BaseMapper<CostAllocation> {

    /**
     * 同一业务来源的分摊是否已记过。
     * <p>给「到货件分摊导入」用：没有这个判定，重复点一次就多算一次头程成本，
     * 而 amz_cost_allocation 上没有 (shop_id, cost_type, source_ref) 唯一键，数据库不会替我们拦。
     */
    @Select("SELECT COUNT(1) FROM amz_cost_allocation "
            + "WHERE shop_id = #{shopId} AND cost_type = #{costType} AND source_ref = #{sourceRef}")
    int countBySource(@Param("shopId") Long shopId, @Param("costType") String costType,
                      @Param("sourceRef") String sourceRef);

    /** 已记过的分摊原文：幂等返回时给调用方账上真正的明细 */
    @Select("SELECT * FROM amz_cost_allocation "
            + "WHERE shop_id = #{shopId} AND cost_type = #{costType} AND source_ref = #{sourceRef} "
            + "ORDER BY id DESC LIMIT 1")
    CostAllocation selectBySource(@Param("shopId") Long shopId, @Param("costType") String costType,
                                  @Param("sourceRef") String sourceRef);
}
