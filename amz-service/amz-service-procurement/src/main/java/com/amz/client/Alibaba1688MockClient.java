package com.amz.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 1688 开放平台模拟客户端。
 * <p>
 * 离线模拟实现，返回构造的 1688 订单号，保证项目可独立运行。
 * 仅在 {@code spring.profiles.active=mock} 时生效。
 */
@Slf4j
@Component
@Profile("mock")
public class Alibaba1688MockClient implements Alibaba1688Client {

    /**
     * 1688 订单号前缀，用于解析订单创建时间戳。
     */
    private static final String ORDER_NO_PREFIX = "1688-";

    @Override
    public String createOrder(String offerId, Integer quantity, BigDecimal unitPrice) {
        // 模拟：实际应调用 alibaba.trade.create，签名 + POST
        String alibabaOrderNo = ORDER_NO_PREFIX + System.currentTimeMillis();
        log.info("1688 采购下单模拟：offerId={} quantity={} price={} → alibabaOrderNo={}",
                offerId, quantity, unitPrice, alibabaOrderNo);
        return alibabaOrderNo;
    }

    /**
     * 模拟查询订单状态。
     * <p>
     * 修复：原实现始终返回 {@code WAIT_SEND}，导致采购单永远卡在 PRODUCING 状态。
     * 现基于订单号中编码的创建时间戳推进状态：
     * <ul>
     *   <li>创建后 &lt; 2 天：{@code WAIT_SEND}（供应商生产中，等待发货）→ 采购单 PRODUCING</li>
     *   <li>创建后 2-5 天：{@code WAIT_RECEIVE}（已发货，待收货）→ 采购单 SHIPPED</li>
     *   <li>创建后 &gt;= 5 天：{@code FINISHED}（交易完成）→ 采购单 QC_PENDING</li>
     * </ul>
     */
    @Override
    public String queryOrderStatus(String alibabaOrderNo) {
        log.info("1688 订单状态查询模拟：alibabaOrderNo={}", alibabaOrderNo);
        long createdMs = parseCreatedMillis(alibabaOrderNo);
        long daysElapsed = Duration.between(Instant.ofEpochMilli(createdMs), Instant.now()).toDays();
        if (daysElapsed < 0) {
            // 时间异常（时钟回拨或订单号伪造），退化为初始状态
            return "WAIT_SEND";
        }
        if (daysElapsed < 2) {
            return "WAIT_SEND";
        }
        if (daysElapsed < 5) {
            return "WAIT_RECEIVE";
        }
        return "FINISHED";
    }

    @Override
    public String queryTrackingNo(String alibabaOrderNo) {
        log.info("1688 物流查询模拟：alibabaOrderNo={}", alibabaOrderNo);
        return "SF" + System.currentTimeMillis();
    }

    @Override
    public boolean closeOrder(String alibabaOrderNo) {
        log.info("1688 关闭订单模拟：alibabaOrderNo={}", alibabaOrderNo);
        return true;
    }

    /**
     * 从订单号解析创建时间戳。订单号格式：{@code 1688-<millis>}。
     * 无法解析时返回当前时间，使状态判定退化为初始状态。
     */
    private long parseCreatedMillis(String alibabaOrderNo) {
        if (alibabaOrderNo == null || !alibabaOrderNo.startsWith(ORDER_NO_PREFIX)) {
            return System.currentTimeMillis();
        }
        try {
            return Long.parseLong(alibabaOrderNo.substring(ORDER_NO_PREFIX.length()));
        } catch (NumberFormatException ex) {
            return System.currentTimeMillis();
        }
    }
}
