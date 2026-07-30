package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 旧版店铺实体(旧 schema: name/image/time/fans/sales/user_id).
 *
 * @deprecated 该实体映射的旧 amz_shop 表结构已废弃, 且其字段(time/fans/sales/user_id)
 *             在任何 amz_shop schema 中均不存在. 新 schema
 *             (shop_name/marketplace_id/region/seller_id/spapi_xxx/status)
 *             请统一使用 amz-service-user 模块下的
 *             com.amz.model.pojo.Shop, 对应建表脚本
 *             07-init-tables-shop.sql(库: amz_user).
 */
@Deprecated
@Data
@TableName("amz_shop")
public class Shop implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 店铺名称
     */
    @TableField("name")
    private String name;

    /**
     * 头像
     */
    @TableField("image")
    private String image;

    /**
     * 成立时间
     */
    @TableField("time")
    private String time;

    /**
     * 粉丝
     */
    @TableField("fans")
    private Integer fans;

    /**
     * 销量
     */
    @TableField("sales")
    private Integer sales;

    /**
     * 用户id
     */
    @TableField("user_id")
    private Integer userId;
}