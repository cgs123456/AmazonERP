package com.amz.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 1688 开放平台模拟客户端。
 * <p>
 * 生产环境对接路径（参考 1688 开放平台文档）：
 * <ol>
 *   <li>alibaba.trade.create - 创建采购单（offerId + 数量 + 收货地址）</li>
 *   <li>alibaba.trade.pay - 触发支付</li>
 *   <li>alibaba.trade.get - 查询订单状态/物流单号</li>
 *   <li>alibaba.trade.close - 关闭订单</li>
 * </ol>
 * 签名方式：SDK 内置 HMAC-SHA1，需 appKey/appSecret。
 * <p>
 * 当前为离线模拟实现，返回构造的 1688 订单号，保证项目可独立运行。
 */
@Slf4j
@Component
public class Alibaba1688Client {

    /**
     * 向 1688 提交采购单。
     *
     * @param offerId   1688 商品 offerId
     * @param quantity  采购数量
     * @param unitPrice 单价（用于校验是否与平台报价一致）
     * @return 1688 平台订单号
     */
    public String createOrder(String offerId, Integer quantity, BigDecimal unitPrice) {
        // 模拟：实际应调用 alibaba.trade.create，签名 + POST
        String alibabaOrderNo = "1688-" + System.currentTimeMillis();
        log.info("1688 采购下单模拟：offerId={} quantity={} price={} → alibabaOrderNo={}",
                offerId, quantity, unitPrice, alibabaOrderNo);
        return alibabaOrderNo;
    }

    /**
     * 查询 1688 订单状态。
     *
     * @param alibabaOrderNo 1688 订单号
     * @return 状态码：WAIT_PAY / WAIT_SEND / WAIT_RECEIVE / FINISHED / CLOSED
     */
    public String queryOrderStatus(String alibabaOrderNo) {
        // 模拟：实际应调用 alibaba.trade.get
        log.info("1688 订单状态查询模拟：alibabaOrderNo={}", alibabaOrderNo);
        return "WAIT_SEND";
    }

    /**
     * 查询物流单号（供应商发货后）。
     */
    public String queryTrackingNo(String alibabaOrderNo) {
        log.info("1688 物流查询模拟：alibabaOrderNo={}", alibabaOrderNo);
        return "SF" + System.currentTimeMillis();
    }

    /**
     * 关闭 1688 订单（取消采购）。
     */
    public boolean closeOrder(String alibabaOrderNo) {
        log.info("1688 关闭订单模拟：alibabaOrderNo={}", alibabaOrderNo);
        return true;
    }
}
