package com.amz.model.pojo;

import lombok.Data;

import java.io.Serializable;

@Data
public class Attention implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Integer id;

    /**
     * 被关注者id
     */
    private Integer otherId;

    /**
     * 关注者id
     */
    private Integer ownId;

    /**
     * 关注时间
     */
    private String time;
}
