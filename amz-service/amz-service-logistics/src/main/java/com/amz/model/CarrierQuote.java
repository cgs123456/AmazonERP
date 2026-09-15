package com.amz.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 物流商报价实体。
 */
@Data
@TableName("amz_carrier_quote")
public class CarrierQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String carrierName;
    private String serviceType;
    private String originPort;
    private String destinationPort;
    private Integer transitDays;
    private BigDecimal pricePerKg;
    private BigDecimal pricePerCbm;
    private BigDecimal minCharge;
    private BigDecimal fuelSurchargeRate;
    private String currency;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String status;

    /**
     * 入库时间。由数据库默认值填充，业务代码不写。
     */
    @TableField(value = "create_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /**
     * 最近修改时间。由 MySQL {@code ON UPDATE CURRENT_TIMESTAMP} 维护。
     * <p>
     * 这里必须禁掉更新写入：实体若把读出来的旧值再写回去，
     * 显式赋值会优先于自动刷新，导致「最近修改时间」永远停在首次读取的那一刻。
     */
    @TableField(value = "update_time", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
