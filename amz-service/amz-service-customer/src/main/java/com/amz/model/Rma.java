package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * RMA 退货标签实体。
 */
@Data
@TableName("amz_rma")
public class Rma implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String rmaNo;
    private String amazonOrderId;
    private String asin;
    private String sku;
    private String returnReason;
    private String returnType;
    private String productCondition;
    private BigDecimal refundAmount;
    private String labelUrl;
    private BigDecimal labelCost;
    private String carrier;
    private String trackingNo;
    private String status;
    private String remark;
}
