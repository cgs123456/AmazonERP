package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 分时调价规则。
 * <p>
 * 示例：凌晨 0-6 点流量低，竞价调低 30%（multiplier=0.7）；
 * 晚高峰 20-23 点转化高，竞价调高 50%（multiplier=1.5）。
 * <p>
 * 执行器每小时扫描规则，对当前小时命中的规则下发到 Advertising API。
 */
@Data
@TableName("amz_ad_bid_schedule")
public class BidSchedule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺 ID */
    private Long shopId;

    /** 广告活动 ID（null 表示作用于该店铺所有活动） */
    private String campaignId;

    /** 生效起始小时（0-23） */
    private Integer startHour;

    /** 生效结束小时（0-23，含） */
    private Integer endHour;

    /** 竞价倍率：1.0=不变，0.7=降30%，1.5=加50% */
    private BigDecimal multiplier;

    /** 是否启用：1=启用 0=停用 */
    private Integer enabled;
}
