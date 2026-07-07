package com.amz.enums;

import lombok.Getter;

@Getter
public enum OrderStatusEnum {
    DUE(0, "待付款"),
    PAID(1, "已付款"),
    CANCEL(2, "已取消");

    private final int code;
    private final String status;

    OrderStatusEnum(int code, String status) {
        this.code = code;
        this.status = status;
    }
}
