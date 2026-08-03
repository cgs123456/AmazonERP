package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("amz_listing_health")
public class ListingHealth {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String asin;
    private String sku;
    private String status;
    private Boolean titleOk;
    private Boolean bulletPointsOk;
    private Boolean descriptionOk;
    private Boolean aplusOk;
    private Boolean imagesOk;
    private Boolean searchTermsOk;
    private String suppressedReason;
    private Integer healthScore;
    private String severity;
    private LocalDateTime checkTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
