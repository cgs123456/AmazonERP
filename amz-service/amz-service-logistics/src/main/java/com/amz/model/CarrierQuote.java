package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

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
}
