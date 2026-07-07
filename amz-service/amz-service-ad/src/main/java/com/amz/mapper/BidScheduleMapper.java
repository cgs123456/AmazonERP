package com.amz.mapper;

import com.amz.model.BidSchedule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分时调价规则 Mapper。
 */
@Mapper
public interface BidScheduleMapper extends BaseMapper<BidSchedule> {

    /**
     * 查询某小时命中的启用规则。
     * 注意：endHour 含，startHour ≤ currentHour ≤ endHour。
     */
    @Select("SELECT * FROM amz_ad_bid_schedule " +
            "WHERE enabled = 1 AND start_hour <= #{hour} AND end_hour >= #{hour}")
    List<BidSchedule> selectEnabledByHour(@Param("hour") int hour);
}
