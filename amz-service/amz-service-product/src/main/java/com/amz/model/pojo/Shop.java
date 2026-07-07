package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("rb_shop")
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
