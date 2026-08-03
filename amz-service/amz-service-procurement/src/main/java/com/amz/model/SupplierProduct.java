package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 供应商-SKU 关联实体。
 */
@Data
@TableName("amz_supplier_product")
public class SupplierProduct implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long supplierId;
    private Long shopId;
    private String sku;
    private String asin;
    private String supplierOfferId;
    private String supplierSkuCode;
    private BigDecimal supplyPrice;
    private Integer moq;
    private Integer leadTimeDays;
    private String packagingSpec;
    private BigDecimal unitWeight;
    private BigDecimal unitVolume;
    private Integer isPreferred;
    private String status;
}
