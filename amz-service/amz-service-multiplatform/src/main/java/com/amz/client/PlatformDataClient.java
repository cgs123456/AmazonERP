package com.amz.client;

import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;

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
}
