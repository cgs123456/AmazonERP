package com.amz.client;

import java.math.BigDecimal;

/**
 * 1688 开放平台客户端接口。
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
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link Alibaba1688MockClient} 离线模拟，保证项目可独立运行</li>
 *   <li>{@code !mock}：{@link Alibaba1688RealClient} 真实 API 对接骨架</li>
 * </ul>
 */
public interface Alibaba1688Client {

    /**
     * 向 1688 提交采购单。
     *
     * @param offerId   1688 商品 offerId
     * @param quantity  采购数量
     * @param unitPrice 单价（用于校验是否与平台报价一致）
     * @return 1688 平台订单号
     */
    String createOrder(String offerId, Integer quantity, BigDecimal unitPrice);

    /**
     * 查询 1688 订单状态。
     *
     * @param alibabaOrderNo 1688 订单号
     * @return 状态码：WAIT_PAY / WAIT_SEND / WAIT_RECEIVE / FINISHED / CLOSED
     */
    String queryOrderStatus(String alibabaOrderNo);

    /**
     * 查询物流单号（供应商发货后）。
     */
    String queryTrackingNo(String alibabaOrderNo);

    /**
     * 关闭 1688 订单（取消采购）。
     */
    boolean closeOrder(String alibabaOrderNo);
}
