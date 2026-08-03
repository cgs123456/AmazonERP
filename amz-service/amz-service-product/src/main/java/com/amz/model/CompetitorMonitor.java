package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("amz_competitor_monitor")
public class CompetitorMonitor {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String competitorAsin;
    private String competitorTitle;
    private BigDecimal price;
    private BigDecimal priceChange;
    private Integer bsRank;
    private Integer reviewCount;
    private BigDecimal reviewRating;
    private BigDecimal ratingChange;
    private Boolean inStock;
    private Boolean hasCoupon;
    private Boolean hasDeal;
    private LocalDate snapshotDate;
    private String marketplaceId;
    private String notes;
    private LocalDateTime createTime;
}
