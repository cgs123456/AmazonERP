package com.amz.enums;

import lombok.Getter;

@Getter
public enum LogType {
    /**
     * 笔记浏览
     */
    SCAN("scan"),

    /**
     * 点赞
     */
    LIKE("like")
    ;

    private final String code;


    LogType(String code) {
        this.code = code;
    }
}
