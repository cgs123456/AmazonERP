package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存调拨单实体。
 */
@Data
@TableName("amz_inventory_transfer")
public class InventoryTransfer implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String transferNo;
    private Long fromWarehouseId;
    private Long toWarehouseId;
    private String asin;
    private String sku;
    private Integer quantity;
    private String carrier;
    private String trackingNo;
    private BigDecimal shippingCost;
    private String status;
    private String remark;

    /**
     * 建单时间。看板用它算「在途已多久」——只看状态看不出单子是不是卡住了。
     */
    @TableField(value = "create_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /**
     * 最近状态变更时间。由 MySQL {@code ON UPDATE CURRENT_TIMESTAMP} 维护，业务代码不写。
     */
    @TableField(value = "update_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
