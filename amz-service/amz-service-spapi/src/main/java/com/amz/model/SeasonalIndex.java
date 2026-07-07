package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 季节性指数实体。
 * <p>
 * 对应 amz_spapi.amz_seasonal_index 表，存储各类目在不同月份的季节性指数（1.0 为正常水平）。
 * 唯一键：category + month。
 */
@Data
@TableName("amz_seasonal_index")
public class SeasonalIndex {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 类目（如 ELECTRONICS / TOYS / OUTDOOR / APPAREL / HOME）。
     */
    @TableField("category")
    private String category;

    /**
     * 月份 1-12。
     */
    @TableField("month")
    private Integer month;

    /**
     * 季节性指数，1.0 表示正常水平。
     */
    @TableField("seasonal_index")
    private BigDecimal seasonalIndex;
}
