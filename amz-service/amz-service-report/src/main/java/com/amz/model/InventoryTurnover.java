package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库存周转报表实体。
 */
@Data
@TableName("amz_inventory_turnover")
public class InventoryTurnover implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String asin;
    private String sku;
    private LocalDate reportDate;
    private BigDecimal avgInventoryValue;
    private BigDecimal cogs;
    private BigDecimal turnoverRate;
    private Integer daysOfSupply;
    private Integer stockoutCount;
    private Integer overstockDays;
    private BigDecimal deadStockValue;
}
