package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("amz_listing_change_log")
public class ListingChangeLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String asin;
    private String sku;
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String changeSource;
    private String operator;
    private LocalDateTime changeTime;
}
