package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 跟卖监控告警实体。
 * 当其他卖家在自建 Listing 上挂卖（抢购物车/稀释销量）时触发告警。
 */
@Data
@TableName("amz_hijack_alert")
public class HijackAlert implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 被跟卖的商品 ASIN */
    private String asin;

    /** 跟卖卖家 ID */
    private String hijackerSellerId;

    /** 跟卖卖家名称 */
    private String hijackerName;

    /** 跟卖价格 */
    private BigDecimal hijackPrice;

    /** 是否抢到购物车：true=已抢走 Buy Box */
    private Boolean buyBoxTaken;

    /** 状态：NEW / HANDLED / IGNORED */
    private String status;
}
