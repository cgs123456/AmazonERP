package com.amz.enums;

import lombok.Getter;

@Getter
public enum LogType {
    /**
     * 商品浏览
     */
    SCAN("scan"),

    /**
     * 商品收藏
     */
    LIKE("like")
    ;

    private final String code;


    LogType(String code) {
        this.code = code;
    }
}
