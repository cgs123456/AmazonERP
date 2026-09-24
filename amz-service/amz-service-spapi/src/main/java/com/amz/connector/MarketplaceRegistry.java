package com.amz.connector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SP-API marketplace → region → 官方主机 的**单一事实源**（P0-36）。
 * <p>
 * 背景：旧实现有 **4 份完全相同**的硬编码表（{@code OrdersClient} / {@code FeedsClient} /
 * {@code FbaInventoryClient} / {@code SpApiGateway}），每份只有 10 条，缺官方 13 条，
 * 且解析统一走 {@code getOrDefault(marketplaceId, "NA")} —— 未登记站点会静默打到北美端点。
 * 例如巴西 {@code A2Q3Y263D00KWC} 属 <b>NA</b> 端点（最易误判），
 * 而 {@code A28R8C7NBKEWEA}(IE) / {@code A19VAU5U5O7RUS}(SG) 等 13 条此前完全不在册。
 * <p>
 * 本类为纯数据 + 静态解析（无 Spring、无 HTTP、无状态），因此可离线单测。
 * 全部解析路径 **fail-closed**：未登记 marketplaceId 抛
 * {@link UnknownMarketplaceException#CODE_UNKNOWN_MARKETPLACE}，不支持 region 抛
 * {@link UnknownMarketplaceException#CODE_UNSUPPORTED_REGION}，**永不**回落默认区域。
 * 不做 trim / 大小写归一：静默归一化会掩盖凭证或请求里的脏数据（宁可显式失败）。
 * <p>
 * 数据来源：SP-API 官方 {@code store-identifiers.md}（2026-09-24 快照，23 个唯一
 * marketplaceId），见设计文档 §1.9.2（2）的表格。国家码沿用官方文档写法（英国记 UK），
 * 不是 ISO-3166 映射。Amazon 新增站点时本表需要显式补充——这是刻意的：
 * 未补表即解析失败，而不是把请求发到错误区域。
 * <p>
 * 与端点覆盖的关系：本类只描述**官方**主机；沙箱/桩地址等覆盖逻辑由后续
 * {@code SpApiEndpointResolver}（Task 11 Step 3）负责，二者不重叠。
 */
public final class MarketplaceRegistry {

    /** 北美区域标识。 */
    public static final String REGION_NA = "NA";

    /** 欧洲区域标识。 */
    public static final String REGION_EU = "EU";

    /** 远东区域标识。 */
    public static final String REGION_FE = "FE";

    /** 官方全表条数（用于自检，防止改表时误删条目）。 */
    private static final int OFFICIAL_MARKETPLACE_COUNT = 23;

    private static final Map<String, String> HOST_BY_REGION;

    static {
        Map<String, String> hosts = new LinkedHashMap<>();
        hosts.put(REGION_NA, "sellingpartnerapi-na.amazon.com");
        hosts.put(REGION_EU, "sellingpartnerapi-eu.amazon.com");
        hosts.put(REGION_FE, "sellingpartnerapi-fe.amazon.com");
        HOST_BY_REGION = Collections.unmodifiableMap(hosts);
    }

    /** marketplaceId → 条目（LinkedHashMap 保持官方表格顺序，便于日志与测试对齐）。 */
    private static final Map<String, Marketplace> BY_ID = buildIndex();

    private MarketplaceRegistry() {
    }

    /**
     * 一条 marketplace 登记项。
     *
     * @param marketplaceId 官方 marketplaceId（如 {@code ATVPDKIKX0DER}）
     * @param region        SP-API 区域：NA / EU / FE
     * @param countryCode   官方文档中的国家码写法（如 US / UK / SG）
     * @param host          官方端点主机名（不含协议），同区域一致
     */
    public record Marketplace(String marketplaceId, String region, String countryCode, String host) {
    }

    private static Map<String, Marketplace> buildIndex() {
        List<String[]> rows = List.of(
                new String[]{"ATVPDKIKX0DER", REGION_NA, "US"},
                new String[]{"A2EUQ1WTGCTBG2", REGION_NA, "CA"},
                new String[]{"A1AM78C64UM0Y8", REGION_NA, "MX"},
                new String[]{"A2Q3Y263D00KWC", REGION_NA, "BR"},
                new String[]{"A1F83G8C2ARO7P", REGION_EU, "UK"},
                new String[]{"A13V1IB3VIYZZH", REGION_EU, "FR"},
                new String[]{"A1PA6795UKMFR9", REGION_EU, "DE"},
                new String[]{"A1RKKUPIHCS9HS", REGION_EU, "ES"},
                new String[]{"APJ6JRA9NG5V4", REGION_EU, "IT"},
                new String[]{"A28R8C7NBKEWEA", REGION_EU, "IE"},
                new String[]{"AMEN7PMS3EDWL", REGION_EU, "BE"},
                new String[]{"A1805IZSGTT6HS", REGION_EU, "NL"},
                new String[]{"A2NODRKZP88ZB9", REGION_EU, "SE"},
                new String[]{"AE08WJ6YKNBMC", REGION_EU, "ZA"},
                new String[]{"A1C3SOZRARQ6R3", REGION_EU, "PL"},
                new String[]{"ARBP9OOSHTCHU", REGION_EU, "EG"},
                new String[]{"A33AVAJ2PDY3EV", REGION_EU, "TR"},
                new String[]{"A17E79C6D8DWNP", REGION_EU, "SA"},
                new String[]{"A2VIGQ35RCS4UG", REGION_EU, "AE"},
                new String[]{"A21TJRUUN4KGV", REGION_EU, "IN"},
                new String[]{"A39IBJ37TRP1C6", REGION_FE, "AU"},
                new String[]{"A1VC38T7YXB528", REGION_FE, "JP"},
                new String[]{"A19VAU5U5O7RUS", REGION_FE, "SG"}
        );

        Map<String, Marketplace> index = new LinkedHashMap<>();
        Map<String, Integer> perRegion = new LinkedHashMap<>();
        for (String[] row : rows) {
            String marketplaceId = row[0];
            String region = row[1];
            String countryCode = row[2];
            String host = HOST_BY_REGION.get(region);
            if (host == null) {
                throw new IllegalStateException("registry 内置区域非法：marketplaceId="
                        + marketplaceId + " region=" + region);
            }
            Marketplace entry = new Marketplace(marketplaceId, region, countryCode, host);
            if (index.put(marketplaceId, entry) != null) {
                throw new IllegalStateException("registry 存在重复 marketplaceId=" + marketplaceId);
            }
            perRegion.merge(region, 1, Integer::sum);
        }

        if (index.size() != OFFICIAL_MARKETPLACE_COUNT) {
            throw new IllegalStateException("registry 条数应等于官方 "
                    + OFFICIAL_MARKETPLACE_COUNT + "，实际 " + index.size());
        }
        int na = perRegion.getOrDefault(REGION_NA, 0);
        int eu = perRegion.getOrDefault(REGION_EU, 0);
        int fe = perRegion.getOrDefault(REGION_FE, 0);
        if (na != 4 || eu != 16 || fe != 3) {
            throw new IllegalStateException("registry 区域分布应为 NA=4 / EU=16 / FE=3，实际 NA="
                    + na + " EU=" + eu + " FE=" + fe);
        }
        return Collections.unmodifiableMap(index);
    }

    /** 全部已登记 marketplaceId（官方表格顺序，只读）。 */
    public static Set<String> marketplaceIds() {
        return BY_ID.keySet();
    }

    /** 全部受支持区域（NA / EU / FE，只读、顺序稳定）。 */
    public static Set<String> regions() {
        return HOST_BY_REGION.keySet();
    }

    /** 某区域下的全部 marketplace（未支持区域即抛异常，不返回空集合）。 */
    public static List<Marketplace> marketplacesByRegion(String region) {
        requireHost(region);
        List<Marketplace> result = new java.util.ArrayList<>();
        for (Marketplace entry : BY_ID.values()) {
            if (entry.region().equals(region)) {
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 解析 marketplaceId，返回 region / 国家码 / 官方主机。
     *
     * @throws UnknownMarketplaceException {@link UnknownMarketplaceException#CODE_UNKNOWN_MARKETPLACE}
     */
    public static Marketplace resolve(String marketplaceId) {
        Marketplace entry = marketplaceId == null ? null : BY_ID.get(marketplaceId);
        if (entry == null) {
            throw new UnknownMarketplaceException(
                    UnknownMarketplaceException.CODE_UNKNOWN_MARKETPLACE,
                    "未登记的 marketplaceId=\"" + marketplaceId + "\"（不在官方 "
                            + OFFICIAL_MARKETPLACE_COUNT + " 条登记表内）：拒绝回落默认区域（fail-closed）",
                    marketplaceId, null);
        }
        return entry;
    }

    /** 解析 marketplaceId → region（fail-closed）。 */
    public static String resolveRegion(String marketplaceId) {
        return resolve(marketplaceId).region();
    }

    /** 解析 marketplaceId → 官方国家码（fail-closed）。 */
    public static String resolveCountryCode(String marketplaceId) {
        return resolve(marketplaceId).countryCode();
    }

    /** 解析 marketplaceId → 官方端点 URL（含 {@code https://}）。 */
    public static String resolveEndpoint(String marketplaceId) {
        return "https://" + resolve(marketplaceId).host();
    }

    /**
     * 解析 region → 官方主机名（不含协议、不含端口）。
     *
     * @throws UnknownMarketplaceException {@link UnknownMarketplaceException#CODE_UNSUPPORTED_REGION}
     */
    public static String resolveHost(String region) {
        return requireHost(region);
    }

    /** 解析 region → 官方端点 URL（含 {@code https://}）。 */
    public static String resolveEndpointForRegion(String region) {
        return "https://" + requireHost(region);
    }

    /**
     * 凭证兜底链（**顺序固定且 fail-closed**）：
     * 显式 marketplaceId → 凭证登记的 marketplaceId → 凭证登记的 region → 抛异常。
     * <p>
     * 旧实现在三者全空时回落 {@code "NA"}，属 fail-open（会把请求发到未经确认的区域）。
     * 这里改为显式失败：宁可让调度器记录一条清晰的配置错误，也不静默打错端点。
     * <p>
     * 注意：显式传入的 marketplaceId 为空白串时视作“未提供”，继续走兜底链；
     * 一旦非空白就必须能解析成功，否则立即抛异常（不继续兜底，避免掩盖调用方 bug）。
     *
     * @param requestedMarketplaceId  调用方显式指定的 marketplaceId（可为 null）
     * @param credentialMarketplaceId 凭证登记的 marketplaceId（可为 null）
     * @param credentialRegion        凭证登记的 region（可为 null）
     * @param shopRef                 仅用于错误消息的调用方标识（如 {@code shopId=1}），不得含密钥
     */
    public static String resolveRegion(String requestedMarketplaceId,
                                       String credentialMarketplaceId,
                                       String credentialRegion,
                                       String shopRef) {
        if (isPresent(requestedMarketplaceId)) {
            return resolveRegion(requestedMarketplaceId);
        }
        if (isPresent(credentialMarketplaceId)) {
            return resolveRegion(credentialMarketplaceId);
        }
        if (isPresent(credentialRegion)) {
            requireHost(credentialRegion);
            return credentialRegion;
        }
        throw new UnknownMarketplaceException(
                UnknownMarketplaceException.CODE_UNSUPPORTED_REGION,
                "凭证既无 marketplaceId 也无 region（" + shopRef
                        + "）：拒绝使用隐式默认区域（fail-closed）",
                null, null);
    }

    private static String requireHost(String region) {
        String host = region == null ? null : HOST_BY_REGION.get(region);
        if (host == null) {
            throw new UnknownMarketplaceException(
                    UnknownMarketplaceException.CODE_UNSUPPORTED_REGION,
                    "不支持的 SP-API region=\"" + region + "\"（仅支持 " + HOST_BY_REGION.keySet()
                            + "）：拒绝回落默认区域（fail-closed）",
                    null, region);
        }
        return host;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** 供测试与诊断使用：区域 → 官方主机（只读）。 */
    public static Map<String, String> hostByRegion() {
        return HOST_BY_REGION;
    }

    /** 供诊断使用：已登记 marketplaceId 的只读快照。 */
    public static Set<String> knownMarketplaceIds() {
        return new LinkedHashSet<>(BY_ID.keySet());
    }
}