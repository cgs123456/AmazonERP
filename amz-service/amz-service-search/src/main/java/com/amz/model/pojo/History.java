package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("rb_history")
public class History implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录
     */
    @TableField("history")
    private String history;

    /**
     * 用户id
     */
    @TableField("user_id")
    private Integer userId;
}

