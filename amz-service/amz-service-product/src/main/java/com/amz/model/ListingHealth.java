package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /**
     * A+ 只在真的拿到结论时才写值。列上的 DEFAULT 1 会让「插入时不带这一列」变成
     * 「已确认正常」，所以这里强制把 null 也写出去（MyBatis-Plus 默认跳过 null 字段）。
     */
    @TableField(insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
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
