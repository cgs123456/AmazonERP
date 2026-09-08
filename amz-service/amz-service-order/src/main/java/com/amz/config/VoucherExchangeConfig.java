package com.amz.config;

import com.amz.constant.MqConstant;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 凭证交换机声明（B1/M8，生产侧）。
 * <p>
 * 交换机由生产/消费两侧各自声明（同名同类型同 durable，重复声明安全）：
 * finance 未就绪时若交换机不存在，发送会报 404 通道异常，
 * 导致订单同步整体回滚——async 解耦名存实亡。此处声明后，
 * finance 停机仅影响消费进度，不阻断订单落库。
 * <p>
 * 队列归消费方（finance）声明绑定，生产侧不越界。
 */
@Configuration
public class VoucherExchangeConfig {

    @Bean
    public DirectExchange voucherExchange() {
        return new DirectExchange(MqConstant.VOUCHER_EXCHANGE, true, false);
    }
}
