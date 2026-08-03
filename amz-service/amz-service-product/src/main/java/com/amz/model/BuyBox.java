package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("amz_buy_box")
public class BuyBox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String asin;
    private String sellerId;
    private Boolean isSelf;
    private BigDecimal buyboxPrice;
    private BigDecimal ourPrice;
    private BigDecimal priceGap;
    private String fulfillmentType;
    private BigDecimal ownershipPct;
    private LocalDateTime snapshotTime;
    private String marketplaceId;
}
