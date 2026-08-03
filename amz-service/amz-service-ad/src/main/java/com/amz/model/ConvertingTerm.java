package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 出单词库实体。
 */
@Data
@TableName("amz_ad_converting_terms")
public class ConvertingTerm implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String asin;
    private String searchTerm;
    private String campaignId;
    private Integer totalOrders;
    private BigDecimal totalSales;
    private BigDecimal totalCost;
    private BigDecimal avgAcos;
    private LocalDate firstSeen;
    private LocalDate lastSeen;
    private Integer isAddedToKeyword;
    private String status;
}
