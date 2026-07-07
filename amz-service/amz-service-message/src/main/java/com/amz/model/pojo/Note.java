package com.amz.model.pojo;

import lombok.Data;

import java.io.Serializable;

@Data
public class Note implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容
     */
    private String content;

    /**
     * 图片
     */
    private String image;

    /**
     * 发布时间
     */
    private String time;

    /**
     * 类型
     */
    private String type;

    /**
     * 地址
     */
    private String address;

    /**
     * 经度
     */
    private Double longitude;

    /**
     * 纬度
     */
    private Double latitude;

    /**
     * 点赞
     */
    private Integer like;

    /**
     * 收藏
     */
    private Integer collection;

    /**
     * 发布人id
     */
    private Integer userId;
}
