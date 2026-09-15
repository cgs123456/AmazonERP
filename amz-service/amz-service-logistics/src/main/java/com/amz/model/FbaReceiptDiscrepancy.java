package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FBA 货件签收差异实体。
 */
@Data
@TableName("amz_fba_receipt_discrepancy")
public class FbaReceiptDiscrepancy implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private Long shipmentId;
    private String asin;
    private String sku;
    private Integer expectedQuantity;
    private Integer receivedQuantity;
    private Integer difference;
    private String discrepancyType;
    private String status;
    private String resolution;

    /** 登记时间 */
    @TableField(value = "create_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 最近处理时间。由 MySQL {@code ON UPDATE CURRENT_TIMESTAMP} 维护，业务代码不写。 */
    @TableField(value = "update_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
