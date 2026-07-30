package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Amazon 店铺实体（ops 模块精简版）。
 * 仅包含定时扫描所需字段，完整字段定义见 user 服务。
 */
@Data
@TableName("amz_shop")
public class Shop {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("status")
    private Integer status;
}