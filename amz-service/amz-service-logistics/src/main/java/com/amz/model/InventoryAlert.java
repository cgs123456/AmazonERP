package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("amz_inventory_alert")
public class InventoryAlert {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shopId;
    private String sku;
    private Long warehouseId;
    private String alertType;
    private BigDecimal thresholdValue;
    private String thresholdUnit;
    private String alertLevel;
    private String notifyChannels;
    private Boolean enabled;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
