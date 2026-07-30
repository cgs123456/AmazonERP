package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 仓库库存实体（WMS 核心）。
 * <p>
 * available_quantity 为数据库生成列（quantity - reserved_quantity），不可写入，仅可读取。
 */
@Data
@TableName("amz_warehouse_inventory")
public class WarehouseInventory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long warehouseId;

    private Long shopId;

    private String sku;

    private String asin;

    private Integer quantity;

    /** 预留数量（已分配订单） */
    private Integer reservedQuantity;

    /** 可用数量（数据库生成列，不可写入） */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer availableQuantity;

    /** 在途数量 */
    private Integer inboundQuantity;

    /** 库位码 */
    private String locationCode;

    /** 批次号 */
    private String batchNo;

    /** 过期日期 */
    private LocalDate expireDate;
}
