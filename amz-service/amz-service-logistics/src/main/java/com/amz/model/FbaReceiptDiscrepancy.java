package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

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
}
