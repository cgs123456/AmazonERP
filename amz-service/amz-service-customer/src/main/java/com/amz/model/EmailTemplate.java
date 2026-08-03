package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 邮件模板实体。
 */
@Data
@TableName("amz_email_template")
public class EmailTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;
    private String templateName;
    private String templateType;
    private String subject;
    private String body;
    private String language;
    private String triggerEvent;
    private Integer triggerDelayHours;
    private Integer enabled;
}
