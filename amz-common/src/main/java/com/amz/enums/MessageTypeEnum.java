package com.amz.enums;

import lombok.Getter;

/**
 * 消息类型枚举
 */
@Getter
public enum MessageTypeEnum {
    INVENTORY_WARNING(0, "库存预警"),
    ORDER_EXCEPTION(1, "订单异常"),
    REPLENISHMENT_SUGGESTION(2, "补货建议"),
    ATTENTION(3, "关注");

    private final int code;
    private final String type;

    MessageTypeEnum(int code, String type) {
        this.code = code;
        this.type = type;
    }
}
