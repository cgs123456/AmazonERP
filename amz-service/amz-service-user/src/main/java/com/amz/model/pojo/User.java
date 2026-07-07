package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("amz_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 手机号
     */
    @TableField("phone")
    private String phone;

    /**
     * 昵称
     */
    @TableField("nickname")
    private String nickname;

    /**
     * 头像
     */
    @TableField("image")
    private String image;

    /**
     * 用户号
     */
    @TableField("number")
    private Long number;

    /**
     * 性别
     */
    @TableField("sex")
    private String sex;

    /**
     * 生日
     */
    @TableField("birthday")
    private String birthday;

    /**
     * 地区
     */
    @TableField("address")
    private String address;

    /**
     * 身份
     */
    @TableField("identity")
    private String identity;

    /**
     * 学校
     */
    @TableField("school")
    private String school;

    /**
     * 注册时间
     */
    @TableField("time")
    private String time;
}
