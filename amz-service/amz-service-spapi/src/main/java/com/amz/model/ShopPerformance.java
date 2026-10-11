package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 店铺绩效原始留痕（P3.1）。
 * <p>
 * <b>诚实边界</b>：GET_V2_SELLER_PERFORMANCE_REPORT 是官方存在的报表类型，
 * 但本仓快照没有其列结构——不编造字段级解析器，报表原文完整入库，
 * 字段级解析等取得真实样本后补（届时新增列，原文不丢）。
 */
@Data
@TableName("amz_shop_performance")
public class ShopPerformance implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private String marketplaceId;

    private String reportType;

    private String reportId;

    private String rawContent;

    private Integer contentLength;

    /** SYNCED / EMPTY */
    private String status;

    private Long operatorId;

    private LocalDateTime createTime;
}
