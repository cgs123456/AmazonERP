package com.amz.connector;

import com.amz.client.FbaInventoryClient;
import com.amz.client.FeedsClient;
import com.amz.client.OrdersClient;
import com.amz.client.SpApiGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-36：marketplace → region → 官方主机 的**单一事实源**与 fail-closed 行为。
 * <p>
 * 用户口径：暂时没有平台 API 凭证，但必须“有 API 即可直接用”。
 * 该口径的工程前提之一是：请求**绝不能**因为映射表缺项而静默打到别的区域端点
 * （旧实现为 4 份各 10 条的副本 + {@code getOrDefault(id, "NA")}，未知 ID 静默回落 NA）。
 * <p>
 * 期望值来源：spec §1.9.2（2）表格，原始出处为 SP-API 官方 {@code store-identifiers.md}
 * （2026-09-24 快照，23 个唯一 marketplaceId）。国家码按官方文档写法（如英国记 UK），
 * 不是 ISO-3166 映射，测试逐条固定以免被“顺手归一化”。
 */
@DisplayName("P0-36 MarketplaceRegistry（官方 23 条全表 + fail-closed）")
class MarketplaceRegistryTest {

    /** 官方全表逐条期望值：marketplaceId / region / 国家码。 */
    private static final List<Row> OFFICIAL = List.of(
            new Row("ATVPDKIKX0DER", "NA", "US"),
            new Row("A2EUQ1WTGCTBG2", "NA", "CA"),
            new Row("A1AM78C64UM0Y8", "NA", "MX"),
            new Row("A2Q3Y263D00KWC", "NA", "BR"),
            new Row("A1F83G8C2ARO7P", "EU", "UK"),
            new Row("A13V1IB3VIYZZH", "EU", "FR"),
            new Row("A1PA6795UKMFR9", "EU", "DE"),
            new Row("A1RKKUPIHCS9HS", "EU", "ES"),
            new Row("APJ6JRA9NG5V4", "EU", "IT"),
            new Row("A28R8C7NBKEWEA", "EU", "IE"),
            new Row("AMEN7PMS3EDWL", "EU", "BE"),
            new Row("A1805IZSGTT6HS", "EU", "NL"),
            new Row("A2NODRKZP88ZB9", "EU", "SE"),
            new Row("AE08WJ6YKNBMC", "EU", "ZA"),
            new Row("A1C3SOZRARQ6R3", "EU", "PL"),
            new Row("ARBP9OOSHTCHU", "EU", "EG"),
            new Row("A33AVAJ2PDY3EV", "EU", "TR"),
            new Row("A17E79C6D8DWNP", "EU", "SA"),
            new Row("A2VIGQ35RCS4UG", "EU", "AE"),
            new Row("A21TJRUUN4KGV", "EU", "IN"),
            new Row("A39IBJ37TRP1C6", "FE", "AU"),
            new Row("A1VC38T7YXB528", "FE", "JP"),
            new Row("A19VAU5U5O7RUS", "FE", "SG")
    );

    private static final List<String> HOSTS = List.of(
            "sellingpartnerapi-na.amazon.com",
            "sellingpartnerapi-eu.amazon.com",
            "sellingpartnerapi-fe.amazon.com");

    private record Row(String marketplaceId, String region, String countryCode) {
    }

    @Test
    @DisplayName("官方 23 条逐条解析：region / 国家码 / host / endpoint")
    void resolvesAllOfficialMarketplaces() {
        assertEquals(23, OFFICIAL.size(), "官方全表应为 23 条（含本轮新增 13 条）");
        assertEquals(23, MarketplaceRegistry.marketplaceIds().size(), "注册表条数必须与官方一致");

        for (Row row : OFFICIAL) {
            MarketplaceRegistry.Marketplace resolved = MarketplaceRegistry.resolve(row.marketplaceId());
            assertEquals(row.region(), resolved.region(), "region 不符：" + row.marketplaceId());
            assertEquals(row.countryCode(), resolved.countryCode(), "国家码不符：" + row.marketplaceId());
            assertEquals(row.region(), MarketplaceRegistry.resolveRegion(row.marketplaceId()),
                    "resolveRegion 不符：" + row.marketplaceId());
            assertEquals(row.countryCode(), MarketplaceRegistry.resolveCountryCode(row.marketplaceId()),
                    "resolveCountryCode 不符：" + row.marketplaceId());
            assertTrue(HOSTS.contains(resolved.host()), "host 必须是官方 NA/EU/FE 主机：" + resolved.host());
            assertEquals("https://" + resolved.host(), MarketplaceRegistry.resolveEndpoint(row.marketplaceId()),
                    "endpoint 必须由 host 派生：" + row.marketplaceId());
            assertEquals(MarketplaceRegistry.resolveHost(row.region()), resolved.host(),
                    "同一 region 的所有 marketplace 必须指向同一 host：" + row.marketplaceId());
        }

        assertEquals(List.of("NA", "EU", "FE"), List.copyOf(MarketplaceRegistry.regions()),
                "区域集合应稳定为 NA/EU/FE");
        assertEquals(4, MarketplaceRegistry.marketplacesByRegion("NA").size(), "NA 应为 4 条");
        assertEquals(16, MarketplaceRegistry.marketplacesByRegion("EU").size(), "EU 应为 16 条");
        assertEquals(3, MarketplaceRegistry.marketplacesByRegion("FE").size(), "FE 应为 3 条");
        assertEquals(List.of("A2Q3Y263D00KWC", "A28R8C7NBKEWEA", "A19VAU5U5O7RUS"),
                List.of("A2Q3Y263D00KWC", "A28R8C7NBKEWEA", "A19VAU5U5O7RUS").stream()
                        .filter(id -> MarketplaceRegistry.marketplaceIds().contains(id))
                        .toList(),
                "本轮必须补齐的 13 条抽样（BR / IE / SG）必须在册");
    }

    @Test
    @DisplayName("未知 marketplaceId 必须抛异常，绝不回落 NA")
    void unknownMarketplaceIdFailsClosed() {
        for (String bad : new String[]{null, "", "   ", "NOT_A_MARKETPLACE", "atvpdkikx0der",
                " ATVPDKIKX0DER", "ATVPDKIKX0DER "}) {
            UnknownMarketplaceException ex = assertThrows(UnknownMarketplaceException.class,
                    () -> MarketplaceRegistry.resolveRegion(bad),
                    "必须拒绝未登记 marketplaceId：" + bad);
            assertEquals(UnknownMarketplaceException.CODE_UNKNOWN_MARKETPLACE, ex.code(),
                    "错误码必须区分“未知 marketplace”：" + bad);
            assertFalse("NA".equals(String.valueOf(ex.getMessage())), "不得把未知 ID 说成 NA");
        }

        UnknownMarketplaceException ex = assertThrows(UnknownMarketplaceException.class,
                () -> MarketplaceRegistry.resolve("NOT_A_MARKETPLACE"));
        assertTrue(ex.getMessage().contains("NOT_A_MARKETPLACE"), "异常消息必须含原始 ID 便于定位：" + ex.getMessage());
    }

    @Test
    @DisplayName("未知 region 必须抛异常，且与未知 marketplace 用不同错误码")
    void unknownRegionFailsClosed() {
        for (String bad : new String[]{null, "", "na", "XX", "NORTH_AMERICA", " us-east-1 "}) {
            UnknownMarketplaceException ex = assertThrows(UnknownMarketplaceException.class,
                    () -> MarketplaceRegistry.resolveHost(bad),
                    "必须拒绝未支持 region：" + bad);
            assertEquals(UnknownMarketplaceException.CODE_UNSUPPORTED_REGION, ex.code(),
                    "错误码必须区分“区域不支持”：" + bad);
        }
        assertEquals("sellingpartnerapi-na.amazon.com", MarketplaceRegistry.resolveHost("NA"));
        assertEquals("https://sellingpartnerapi-fe.amazon.com", MarketplaceRegistry.resolveEndpointForRegion("FE"));
    }

    @Test
    @DisplayName("凭证兜底链 fail-closed：marketplaceId → 凭证 marketplaceId → 凭证 region → 抛异常")
    void credentialFallbackChainFailsClosed() {
        assertEquals("NA", MarketplaceRegistry.resolveRegion("A2Q3Y263D00KWC", null, null, "shopId=1"));
        assertEquals("NA", MarketplaceRegistry.resolveRegion(null, "ATVPDKIKX0DER", null, "shopId=2"));
        assertEquals("EU", MarketplaceRegistry.resolveRegion(null, null, "EU", "shopId=3"));
        assertEquals("FE", MarketplaceRegistry.resolveRegion("A19VAU5U5O7RUS", "ATVPDKIKX0DER", "EU", "shopId=4"),
                "显式 marketplaceId 优先级最高");

        UnknownMarketplaceException noRegion = assertThrows(UnknownMarketplaceException.class,
                () -> MarketplaceRegistry.resolveRegion(null, null, null, "shopId=7"));
        assertEquals(UnknownMarketplaceException.CODE_UNSUPPORTED_REGION, noRegion.code());
        assertTrue(noRegion.getMessage().contains("shopId=7"), "异常消息必须含店铺标识：" + noRegion.getMessage());
        assertFalse(noRegion.getMessage().contains("NA"), "不得暗示回落 NA：" + noRegion.getMessage());

        UnknownMarketplaceException badCredentialId = assertThrows(UnknownMarketplaceException.class,
                () -> MarketplaceRegistry.resolveRegion(null, "NOT_A_MARKETPLACE", "EU", "shopId=8"));
        assertEquals(UnknownMarketplaceException.CODE_UNKNOWN_MARKETPLACE, badCredentialId.code());

        UnknownMarketplaceException badCredentialRegion = assertThrows(UnknownMarketplaceException.class,
                () -> MarketplaceRegistry.resolveRegion(null, null, "na", "shopId=9"));
        assertEquals(UnknownMarketplaceException.CODE_UNSUPPORTED_REGION, badCredentialRegion.code());
    }

    @Test
    @DisplayName("四个客户端不再持有映射副本（字段 + 源码双断言）")
    void clientsNoLongerOwnMappingCopies() throws IOException {
        Set<String> forbiddenFields = Set.of("MARKETPLACE_REGION", "SPAPI_ENDPOINTS");
        for (Class<?> type : List.of(OrdersClient.class, FeedsClient.class,
                FbaInventoryClient.class, SpApiGateway.class)) {
            for (Field field : type.getDeclaredFields()) {
                assertFalse(forbiddenFields.contains(field.getName()),
                        type.getSimpleName() + " 仍持有映射副本字段：" + field.getName());
            }
        }

        Path mainSources = sourceRoot();
        assertEquals(List.of(), grep(mainSources, line -> line.contains("MARKETPLACE_REGION")),
                "全仓主源码不得再有 MARKETPLACE_REGION 副本");
        assertEquals(List.of(), grep(mainSources, line -> line.contains("SPAPI_ENDPOINTS")),
                "全仓主源码不得再有 SPAPI_ENDPOINTS 副本");
        assertEquals(List.of(), grep(mainSources, line -> line.contains("mapMarketplaceToRegion")),
                "映射必须直接走 MarketplaceRegistry，客户端不得再暴露映射方法");
        assertEquals(List.of(), grep(mainSources,
                        line -> !isCommentLine(line)
                                && line.contains("getOrDefault(") && line.contains("\"NA\"")),
                "fail-open 的 getOrDefault(..., \"NA\") 必须消失（只看代码行，不看注释）");
    }

    @Test
    @DisplayName("官方主机字面量只允许出现在 MarketplaceRegistry")
    void hostLiteralsAreSingleSourced() throws IOException {
        List<String> hits = grep(sourceRoot(), line -> line.contains("sellingpartnerapi-"));
        assertFalse(hits.isEmpty(), "registry 自身必须持有官方主机字面量");
        for (String hit : hits) {
            assertTrue(hit.startsWith("MarketplaceRegistry.java:"),
                    "官方主机不得在 registry 之外硬编码：" + hit);
        }
    }

    /** 注释行（javadoc / 行注释 / 块注释起始）不参与“代码里不得再出现该写法”的断言。 */
    private static boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//");
    }

    private static Path sourceRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++, dir = dir.getParent()) {
            Path marker = dir.resolve("src/main/java/com/amz/client/OrdersClient.java");
            if (Files.isRegularFile(marker)) {
                return dir.resolve("src/main/java");
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位 spapi 模块 src/main/java："
                + System.getProperty("user.dir"));
    }

    private static List<String> grep(Path root, Predicate<String> predicate) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (predicate.test(lines.get(i))) {
                        hits.add(file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        return hits;
    }
}