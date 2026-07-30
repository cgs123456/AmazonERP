package com.amz.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

/**
 * 1688 开放平台真实 API 客户端骨架。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 待实现项：
 * <ul>
 *   <li>HMAC-SHA1 签名（appKey/appSecret）</li>
 *   <li>alibaba.trade.create / alibaba.trade.get / alibaba.trade.close 调用</li>
 *   <li>access_token 刷新机制</li>
 * </ul>
 * 在真实 API 未对接前，所有方法不再抛出 {@link UnsupportedOperationException}，
 * 改为打 warn 日志并返回 mock 默认值，避免上游因未对接 API 抛异常中断采购流程。
 * <p>
 * 降级策略参照 {@code KingdeeRealClient}：
 * <ul>
 *   <li>{@link #createOrder} 返回 {@code 1688_MOCK_} 前缀占位订单号（上游可据此识别为占位）</li>
 *   <li>{@link #queryOrderStatus} 返回 {@code WAIT_SEND} 初始状态（与 MockClient 一致，避免误判为已完成）</li>
 *   <li>{@link #queryTrackingNo} 返回 {@code null}（表示无运单信息，上游可降级为不更新物流）</li>
 *   <li>{@link #closeOrder} 返回 {@code false}（上游可据此决定是否重试）</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("!mock")
public class Alibaba1688RealClient implements Alibaba1688Client {

    @Value("${alibaba.app-key:}")
    private String appKey;

    @Value("${alibaba.app-secret:}")
    private String appSecret;

    @Value("${alibaba.gateway:https://gw.open.1688.com/openapi}")
    private String gateway;

    /**
     * 真实实现中可注入 RestTemplate / OkHttp；当前为骨架，未配置 Bean 时不影响编译。
     */
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String createOrder(String offerId, Integer quantity, BigDecimal unitPrice) {
        // 真实 1688 API 待对接：打 warn 日志，返回 1688_MOCK_ 前缀占位订单号，
        // 上游可据此识别为占位编号而非真实 1688 订单号，避免误更新采购单状态为已下单。
        String mockOrderNo = "1688_MOCK_" + System.currentTimeMillis();
        log.warn("真实 1688 采购下单 API 待对接，使用 mock 数据降级：offerId={} quantity={} price={} → alibabaOrderNo={}（gateway={}）",
                offerId, quantity, unitPrice, mockOrderNo, gateway);
        return mockOrderNo;
    }

    @Override
    public String queryOrderStatus(String alibabaOrderNo) {
        // 真实 1688 API 待对接：打 warn 日志，返回 WAIT_SEND 初始状态（与 MockClient 初始状态一致），
        // 避免上游误判为已完成或已关闭而触发后续质检流程。
        log.warn("真实 1688 订单状态查询 API 待对接，使用 mock 数据降级：queryOrderStatus 返回 WAIT_SEND alibabaOrderNo={}",
                alibabaOrderNo);
        return "WAIT_SEND";
    }

    @Override
    public String queryTrackingNo(String alibabaOrderNo) {
        // 真实 1688 API 待对接：打 warn 日志，返回 null 表示无运单信息，
        // 上游可据此降级为不更新物流单号而非抛异常。
        log.warn("真实 1688 物流单号查询 API 待对接，使用 mock 数据降级：queryTrackingNo 返回 null alibabaOrderNo={}",
                alibabaOrderNo);
        return null;
    }

    @Override
    public boolean closeOrder(String alibabaOrderNo) {
        // 真实 1688 API 待对接：打 warn 日志，返回 false 降级，避免抛异常；
        // 上游可据此决定是否重试，而非强制中断取消采购流程。
        log.warn("真实 1688 关闭订单 API 待对接，使用 mock 数据降级：closeOrder 返回 false alibabaOrderNo={}",
                alibabaOrderNo);
        return false;
    }
}
