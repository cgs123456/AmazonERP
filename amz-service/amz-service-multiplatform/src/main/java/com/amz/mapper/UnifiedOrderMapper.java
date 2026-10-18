package com.amz.mapper;

import com.amz.model.UnifiedOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 多平台统一订单 Mapper。
 */
@Mapper
public interface UnifiedOrderMapper extends BaseMapper<UnifiedOrder> {

    /**
     * 该店铺实际存在哪些平台。
     * <p>
     * §S33.87：必须用 {@code DISTINCT} 而不是「取前 N 行再在内存里去重」。
     * 旧实现在 {@code PlatformOrderStatsService#discoverPlatforms} 里
     * {@code SELECT platform ... LIMIT 5001} 后内存去重——一旦店铺订单超过上限，
     * <b>只出现在 5001 行之后的平台会被整个漏掉</b>，而且不会标 truncated：
     * 统计结果看起来是完整的，实际上少了一整个平台的订单。
     * <p>
     * {@code DISTINCT} 让「有多少行」不再影响「有哪些平台」，行数上限只约束统计本身。
     * 走 {@code idx_shop(shop_id)}。
     */
    @Select("SELECT DISTINCT platform FROM amz_unified_order "
            + "WHERE shop_id = #{shopId} AND platform IS NOT NULL AND platform <> '' "
            + "ORDER BY platform")
    List<String> selectDistinctPlatforms(@Param("shopId") Long shopId);
}
