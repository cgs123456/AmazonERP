package com.amz.client.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keepa mock 响应形状契约：mock 必须与真实 Keepa /product 响应同形状，
 * 让 demo 档走真实客户端同一条解析路径（KeepaCompetitorScheduler.parseKeepaStats）。
 *
 * <p>形状来源：Keepa product object 官方文档的 products[].stats.current[] 索引布局，
 * 索引语义由仓内 scheduler 的解析逻辑钉死：1=New 价（美分）、3=SalesRank、
 * 16=评论数、18=评分千分位（4500=4.50）。</p>
 */
@DisplayName("Keepa mock 必须是官方 product/stats 形状且确定性")
class KeepaMockClientShapeTest {

    private final KeepaMockClient client = new KeepaMockClient();

    @Test
    @DisplayName("响应包含官方 products[0].stats.current 索引并带 synthetic 标记")
    void responseMatchesOfficialKeepaProductShape() {
        String body = client.getCompetitorAnalysis("B0SYNTEST01", 1);

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        assertTrue(root.get("synthetic").getAsBoolean(), "必须显式标记 synthetic=true");
        assertTrue(root.has("products"), "官方 /product 响应根字段是 products 数组");
        assertEquals(1, root.getAsJsonArray("products").size());

        JsonObject product = root.getAsJsonArray("products").get(0).getAsJsonObject();
        assertEquals("B0SYNTEST01", product.get("asin").getAsString());
        assertTrue(product.has("stats"), "product 必须带 stats 对象");

        var current = product.getAsJsonObject("stats").getAsJsonArray("current");
        assertTrue(current.size() >= 19, "official current 数组至少覆盖索引 0-18，实际 size=" + current.size());
        // Keepa 官方单位：current[1]=美分、current[18]=千分位评分
        assertEquals(2999, current.get(1).getAsInt(), "current[1] 必须是美分价");
        assertEquals(15234, current.get(3).getAsInt(), "current[3] 必须是 SalesRank");
        assertEquals(1287, current.get(16).getAsInt(), "current[16] 必须是评论数");
        assertEquals(4500, current.get(18).getAsInt(), "current[18] 必须是 RatingMilli");
        // 标题走既有 SYN 识别机制
        assertTrue(product.get("title").getAsString().startsWith("SYN "), "标题必须带 SYN 前缀");
    }

    @Test
    @DisplayName("同参数重复调用结果逐字节一致（确定性，不再随机）")
    void repeatedCallsAreDeterministic() {
        String first = client.getCompetitorAnalysis("B0SYNTEST01", 1);
        String second = client.getCompetitorAnalysis("B0SYNTEST01", 1);
        assertEquals(first, second, "mock 输出必须确定性");
    }

    @Test
    @DisplayName("mock 输出形状与生成器 fixture 逐字段一致（单一事实源）")
    void mockMatchesGeneratedFixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/keepa-product-stats.json")) {
            assertNotNull(in, "fixture missing: /contracts/keepa-product-stats.json");
            JsonObject fixture = JsonParser.parseString(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject mockOutput = JsonParser.parseString(
                    client.getCompetitorAnalysis("B0SYN000001", 1)).getAsJsonObject();
            // fixture 的产品字段必须与 mock 输出一致（synthetic 标记 + 官方索引值）
            assertEquals(fixture.get("synthetic"), mockOutput.get("synthetic"));
            JsonObject fixtureProduct = fixture.getAsJsonArray("products").get(0).getAsJsonObject();
            JsonObject mockProduct = mockOutput.getAsJsonArray("products").get(0).getAsJsonObject();
            assertEquals(fixtureProduct.getAsJsonObject("stats").getAsJsonArray("current"),
                    mockProduct.getAsJsonObject("stats").getAsJsonArray("current"));
        }
    }

    @Test
    @DisplayName("price/rank 两方法同样返回官方形状")
    void priceAndRankMethodsUseOfficialShape() {
        for (String body : new String[]{
                client.getPriceHistory("B0SYNTEST01", 1),
                client.getRankHistory("B0SYNTEST01", 1)}) {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            assertTrue(root.get("synthetic").getAsBoolean());
            assertTrue(root.has("products"));
        }
    }
}
