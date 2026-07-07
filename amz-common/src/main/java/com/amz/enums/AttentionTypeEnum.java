package com.amz.enums;

import lombok.Getter;

/**
 * 关注类型枚举类
 */
@Getter
public enum AttentionTypeEnum {
    OWN(0, "自己"),
    ATTENTION(1, "关注"),
    INATTENTION(2, "非关注");

    private final int code;
    private final String message;
    AttentionTypeEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
