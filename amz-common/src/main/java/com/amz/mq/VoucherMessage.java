package com.amz.mq;

import java.math.BigDecimal;

/**
 * 凭证生成 MQ 消息契约（B1，单源真相）。
 * <p>
 * 生产方（order {@code OrderServiceImpl#publishVoucherMessage}）与消费方
 * （finance {@code FinanceVoucherConsumer}）共用本 record，
 * 字段增删改由编译器两侧同时约束，杜绝 JSON 键名漂移导致静默丢字段。
 * <p>
 * JSON 示例：{"shopId":1, "amazonOrderId":"114-xxx", "totalAmount":29.99, "currency":"USD"}
 */
public record VoucherMessage(
        Long shopId,
        String amazonOrderId,
        BigDecimal totalAmount,
        String currency
) {
}
