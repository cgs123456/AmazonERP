package com.amz.client;

/**
 * Keepa 第三方数据客户端。提供亚马逊商品历史价格、排名、BuyBox 数据。
 */
public interface KeepaClient {
    /** 是否具备真实调用条件；模拟实现始终可用。 */
    default boolean isAvailable() {
        return true;
    }

    /** 获取商品历史价格 */
    String getPriceHistory(String asin, int domain);
    /** 获取商品排名趋势 */
    String getRankHistory(String asin, int domain);
    /** 获取竞品分析 */
    String getCompetitorAnalysis(String asin, int domain);
}
