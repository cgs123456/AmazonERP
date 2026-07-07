package com.amz.enums;

import lombok.Getter;

/**
 * 消息类型枚举（ERP 语义）。
 * <p>
 * 替代原社交场景的 LIKE/COLLECTION/ATTENTION，
 * 用于站内消息推送的分类标识。
 */
@Getter
public enum MessageTypeEnum {
    INVENTORY_ALERT(0, "库存预警"),
    ORDER_EXCEPTION(1, "订单异常"),
    REPLENISH_SUGGEST(2, "补货建议"),
    NEGATIVE_REVIEW(3, "差评告警"),
    PRICE_CHANGE(4, "价格异动");

    private final int code;
    private final String type;

    MessageTypeEnum(int code, String type) {
        this.code = code;
        this.type = type;
    }
}
