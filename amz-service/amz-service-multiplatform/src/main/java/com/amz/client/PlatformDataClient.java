package com.amz.client;

import com.amz.model.UnifiedOrder;
import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;

import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 平台侧"订单之外"的数据拉取契约（商品、站内信、库存）。
 * <p>
 * 存在动因（2026-10-02 功能覆盖清点）：这三类同步原先直接在 {@code MultiplatformServiceImpl}
 * 里 {@code for i=1..N} 造硬编码数据写库，**与 profile 无关**——生产环境同样会写入
 * 假商品、假站内信和随机库存数量，而 {@code platform_product} 还承载平台→ASIN/SKU 映射，
 * 假数据会污染映射本身。契约收敛到客户端层后：mock 实现给样例数据，真实实现要么真拉，
 * 要么显式失败（沿用 {@link AbstractPlatformClient} 与 {@code TemuClient} 既有约定：
 * 禁止返回 null、空集合或伪造数据）。
 */
public interface PlatformDataClient {

    /**
     * 拉取平台在售商品。
     *
     * @param shopId 店铺 ID
     * @return 平台商品列表；未实现时应抛出异常而不是返回空集合
     */
    List<PlatformProduct> fetchProducts(Long shopId);

    /**
     * 拉取平台站内信/买家消息。
     *
     * @param shopId 店铺 ID
     * @return 平台消息列表
     */
    List<PlatformMessage> fetchMessages(Long shopId);

    /**
     * 拉取平台各仓库存快照。
     *
     * @param shopId 店铺 ID
     * @return 平台库存列表
     */
    List<PlatformInventory> fetchInventory(Long shopId);

    /**
     * 拉取最近订单。三家真实客户端都已实现（带各自签名），这里提到公共契约上
     * 是为了让 {@link #probeConnection(Long)} 能复用一次真实的已鉴权读，而不是新造一个端点。
     */
    List<UnifiedOrder> fetchRecentOrders(Long shopId);

    /**
     * 连接探测：真的向平台发一次已鉴权的只读请求，能不能回话就是答案。
     *
     * <p>刻意复用订单读，而不是编一个 "ping/health" 路径——平台没有那个端点时，
     * 探测会永远失败或被写成永远成功，两种都比"不探测"更坏。
     * 凭证缺失、签名被拒、网络不通都归为 false 并记 WARN：探测的职责就是给结论，
     * 不是把失败抛给调用方去猜。
     * <p>
     * 日志用实现类的简单名（{@code TemuRealClient} / {@code TemuMockClient}）标识平台，
     * 而不是新加一个 {@code getPlatformName()}：抽象基类那个 {@code getPlatform()} 是
     * protected，接口方法要求 public，凭空加一个公开重复访问器只会多出一处能被改写的口径。
     * 这里直接用 SLF4J 而不用 {@code java.util.logging}——接口无法继承子类的 {@code @Slf4j}
     * 字段，而 JUL 的记录不进本项目的 logback 管线，失败原因会掉出应用日志。
     */
    default boolean probeConnection(Long shopId) {
        try {
            fetchRecentOrders(shopId);
            return true;
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(PlatformDataClient.class).warn(
                    "平台连接探测失败（复用订单读）impl={} shopId={} 原因={}",
                    getClass().getSimpleName(), shopId, e.getMessage());
            return false;
        }
    }
}
