package com.amz.model.pojo;

import lombok.Data;

import java.io.Serializable;

@Data
public class Message implements Serializable {
    /**
     * 消息类型
     */
    private Integer type;

    /**
     * 通知对象ID
     */
    private Integer noticeId;

    /**
     * 消息对象
     */
    private Object obj;
}
