package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 海外仓实体。
 * <p>
 * 支持三种类型：FBA(Amazon 自营) / AWD(Amazon Warehousing and Distribution) / THIRD_PARTY(第三方海外仓)。
 * 状态流转：ACTIVE(启用) → INACTIVE(停用)
 */
@Data
@TableName("amz_warehouse")
public class Warehouse implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String warehouseName;

    private String warehouseCode;

    /** 仓库类型：FBA / AWD / THIRD_PARTY */
    private String warehouseType;

    private String country;

    private String city;

    private String address;

    private String contactName;

    private String contactPhone;

    /** 容量（立方米） */
    private BigDecimal capacityCbm;

    /** 已用容量（立方米） */
    private BigDecimal usedCbm;

    /** 状态：ACTIVE / INACTIVE */
    private String status;
}
