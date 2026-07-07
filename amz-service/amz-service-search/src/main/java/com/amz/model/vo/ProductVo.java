package com.amz.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 商品搜索返回 VO（替代原 NoteVo）。
 */
@Data
public class ProductVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String content;
    private String summary;
    private String image;
    private Double price;
    private String sku;
    private Long shopId;
    private Integer userId;

    /** 关联用户信息（搜索结果展示用） */
    private Object user;
}
