package com.amz.model.pojo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class Like implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Integer id;

    /**
     * 笔记id
     */
    private Long noteId;

    private Integer ownId;

    /**
     * 用户id
     */
    private Integer userId;

    private Date createTime;
}
