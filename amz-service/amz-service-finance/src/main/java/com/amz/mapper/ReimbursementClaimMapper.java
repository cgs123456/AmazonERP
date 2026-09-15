package com.amz.mapper;

import com.amz.model.ReimbursementClaim;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReimbursementClaimMapper extends BaseMapper<ReimbursementClaim> {

    /**
     * 该差异候选是否已生成索赔单（防止同一候选反复建单）。
     */
    @Select("SELECT COUNT(1) FROM amz_reimbursement_claim WHERE shop_id = #{shopId} "
            + "AND discrepancy_id = #{discrepancyId}")
    int countByDiscrepancy(@Param("shopId") Long shopId,
                           @Param("discrepancyId") Long discrepancyId);
}
