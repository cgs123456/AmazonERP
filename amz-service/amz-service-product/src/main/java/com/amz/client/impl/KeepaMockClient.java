package com.amz.client.impl;

import com.amz.client.KeepaClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Keepa 模拟客户端（mock profile）。
 * <p>
 * 输出与真实 Keepa {@code /product} 响应<b>同形状</b>：
 * {@code products[0].stats.current[]} 官方索引布局（1=New 价美分、3=SalesRank、
 * 16=评论数、18=评分千分位），因此 demo 档与真实客户端共用
 * {@code KeepaCompetitorScheduler.parseKeepaStats} 同一条解析路径。
 * 输出全部确定性（无随机数）、显式 {@code synthetic=true}、标题带既有 SYN 识别前缀。
 */
@Component
@Profile("mock")
public class KeepaMockClient implements KeepaClient {

    /** official current[] 索引：New price（美分）。 */
    static final int INDEX_NEW_PRICE = 1;
    /** official current[] 索引：SalesRank。 */
    static final int INDEX_SALES_RANK = 3;
    /** official current[] 索引：ReviewCount。 */
    static final int INDEX_REVIEW_COUNT = 16;
    /** official current[] 索引：RatingMilli（4500 = 4.50）。 */
    static final int INDEX_RATING_MILLI = 18;

    private static final int SYNTHETIC_PRICE_CENTS = 2999;
    private static final int SYNTHETIC_SALES_RANK = 15234;
    private static final int SYNTHETIC_REVIEW_COUNT = 1287;
    private static final int SYNTHETIC_RATING_MILLI = 4500;
    private static final int CURRENT_SIZE = 19;

    @Override
    public String getPriceHistory(String asin, int domain) {
        return productResponse(asin, domain);
    }

    @Override
    public String getRankHistory(String asin, int domain) {
        return productResponse(asin, domain);
    }

    @Override
    public String getCompetitorAnalysis(String asin, int domain) {
        return productResponse(asin, domain);
    }

    private static String productResponse(String asin, int domain) {
        JsonArray current = new JsonArray();
        for (int i = 0; i < CURRENT_SIZE; i++) {
            // Keepa 约定：缺失指标用 -1 占位
            current.add(-1);
        }
        current.set(INDEX_NEW_PRICE, new com.google.gson.JsonPrimitive(SYNTHETIC_PRICE_CENTS));
        current.set(INDEX_SALES_RANK, new com.google.gson.JsonPrimitive(SYNTHETIC_SALES_RANK));
        current.set(INDEX_REVIEW_COUNT, new com.google.gson.JsonPrimitive(SYNTHETIC_REVIEW_COUNT));
        current.set(INDEX_RATING_MILLI, new com.google.gson.JsonPrimitive(SYNTHETIC_RATING_MILLI));

        JsonObject product = new JsonObject();
        product.addProperty("asin", asin);
        product.addProperty("title", "SYN Competitor Sample Product (offline demo)");
        product.addProperty("domainId", domain);
        JsonObject stats = new JsonObject();
        stats.add("current", current);
        product.add("stats", stats);

        JsonObject root = new JsonObject();
        root.addProperty("synthetic", true);
        JsonArray products = new JsonArray();
        products.add(product);
        root.add("products", products);
        return root.toString();
    }
}
