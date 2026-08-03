package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 供应商档案实体。
 */
@Data
@TableName("amz_supplier")
public class Supplier implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String supplierName;
    private String supplierCode;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String address;
    private String alibabaShopUrl;
    private String alibabaMemberId;
    private String paymentTerms;

    /** 综合评分(0-5) */
    private BigDecimal rating;
    /** 准时交货率(%) */
    private BigDecimal onTimeDeliveryRate;
    /** 质量合格率(%) */
    private BigDecimal qualityPassRate;
    /** 价格竞争力评分(0-5) */
    private BigDecimal priceCompetitiveness;
    /** 响应速度评分(0-5) */
    private BigDecimal responseSpeed;

    private Integer totalOrders;
    private BigDecimal totalAmount;
    private String status;
    private String remark;
}
