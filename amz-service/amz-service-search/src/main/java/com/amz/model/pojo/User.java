package com.amz.model.pojo;

import lombok.Data;

import java.io.Serializable;

@Data
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Integer id;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像
     */
    private String image;

    /**
     * 用户号
     */
    private Long number;

    /**
     * 性别
     */
    private String sex;

    /**
     * 生日
     */
    private String birthday;

    /**
     * 地区
     */
    private String address;

    /**
     * 身份
     */
    private String identity;

    /**
     * 学校
     */
    private String school;

    /**
     * 注册时间
     */
    private String time;
}
