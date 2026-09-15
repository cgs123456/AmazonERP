package com.amz.mapper;

import com.amz.model.FeeDiscrepancy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FeeDiscrepancyMapper extends BaseMapper<FeeDiscrepancy> {

    /**
     * 该 SKU + 差异类型是否已有未结案候选。
     * <p>
     * 扫描是反复执行的（每日），不能每次都给同一笔多收再生成一条候选 ——
     * 否则索赔清单会被重复项淹没，而且同一笔钱可能被提交两次。
     * 已赔付 / 已排除的不计入，允许后续重新识别。
     */
    @Select("SELECT COUNT(1) FROM amz_fee_discrepancy "
            + "WHERE shop_id = #{shopId} AND discrepancy_type = #{type} "
            + "AND status IN ('CANDIDATE','CLAIMED') "
            + "AND ((sku IS NULL AND #{sku} IS NULL) OR sku = #{sku})")
    int countOpenBySkuAndType(@Param("shopId") Long shopId,
                              @Param("sku") String sku,
                              @Param("type") String type);

    /**
     * 入库短收按货件去重（同一货件同一 SKU 只记一次）。
     */
    @Select("SELECT COUNT(1) FROM amz_fee_discrepancy "
            + "WHERE shop_id = #{shopId} AND discrepancy_type = 'INBOUND_SHORTAGE' "
            + "AND shipment_id = #{shipmentId} AND sku = #{sku}")
    int countInboundShortage(@Param("shopId") Long shopId,
                            @Param("shipmentId") String shipmentId,
                            @Param("sku") String sku);
}
