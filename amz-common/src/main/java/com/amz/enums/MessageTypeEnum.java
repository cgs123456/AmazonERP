package com.amz.enums;

import lombok.Getter;

/**
 * 消息类型枚举
 */
@Getter
public enum MessageTypeEnum {
    LIKE(0, "点赞"),
    COLLECTION(1, "收藏"),
    ATTENTION(2, "关注");

    private final int code;
    private final String type;

    MessageTypeEnum(int code, String type) {
        this.code = code;
        this.type = type;
    }
}
