package com.amz.model.pojo;

import lombok.Data;

import java.io.Serializable;

@Data
public class Collection implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Integer id;

    /**
     * 笔记id
     */
    private Long noteId;

    /**
     * 用户id
     */
    private Integer userId;
}
