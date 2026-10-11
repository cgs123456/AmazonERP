package com.amz.multiplatform;

/**
 * 多平台适配层骨架（P4.2）。
 * <p>
 * <b>本轮只做接口声明，不接线</b>：Temu/TikTok/Shein 三个 Real/Mock 客户端是
 * 已验证的运行代码，强行迁到适配器是一次纯重构——收益（新平台=一个 client）
 * 要等第四家平台接入时才能兑现，风险（三平台回归）现在就要付。
 * 本接口固化「新平台需要实现什么」的形状，作为接入第四家平台时的验收清单。
 * <p>
 * <b>形状来源</b>：现有 MultiplatformController 的六类动作
 * （订单同步/发货回传、商品同步、消息收发、库存同步、Webhook 验签、OAuth）。
 */
public interface PlatformAdapter {

    /** 平台标识（TEMU/TIKTOK/SHEIN/...，与 amz_platform_account.platform 对齐）。 */
    String platform();

    /** 该平台已接入的能力声明（供连接器中心与「刻意不给按钮」清单对齐）。 */
   Capabilities capabilities();

    /** 能力面：接入第四家平台时逐项核对，缺一即 UnsupportedOperationException（fail-closed）。 */
    interface Capabilities {
        boolean syncOrders();

        boolean markShipped();

        boolean syncProducts();

        boolean syncMessages();

        boolean replyMessage();

        boolean syncInventory();

        boolean webhookVerified();
    }
}
