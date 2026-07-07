package com.amz.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("amz_attention")
public class Attention implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 被关注者id
     */
    @TableField("other_id")
    private Integer otherId;

    /**
     * 关注者id
     */
    @TableField("own_id")
    private Integer ownId;

    /**
     * 关注时间
     */
    @TableField("time")
    private String time;
}
