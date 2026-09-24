package com.amz.connector;

import com.amz.config.SpApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;

/**
 * SP-API 端点解析：默认官方主机，仅在<b>非生产</b>允许显式覆盖（P0-51）。
 * <p>
 * <b>为什么需要覆盖：</b>没有覆盖缝时，客户端只能连官方主机——本地桩、录制回放代理、
 * 沙箱网关都无法接入，A1/A7/A8 类证据连 E2（桩回放）都取不到（spec §1.9.1 缺口 G1）。
 * <p>
 * <b>安全边界（三条硬规则，全部 fail-closed）：</b>
 * <ol>
 *   <li><b>生产禁止覆盖</b>：prod profile 下 {@code spapi.base-url-override} 或
 *       {@code spapi.lwa-endpoint-override} 任一非空 → 构造 Bean 时抛
 *       {@link IllegalStateException}，应用<b>拒绝启动</b>（不是打印警告后继续跑）；</li>
 *   <li><b>非生产也限主机</b>：覆盖主机必须落在 {@link SpApiHostPolicy} 内（官方主机 +
 *       默认回环 {@code 127.0.0.1}/{@code localhost}/{@code ::1} + {@code spapi.allowlist} 追加项），
 *       否则抛 {@link SpApiEndpointNotAllowedException}；</li>
 *   <li><b>覆盖不放松请求头规则</b>：非官方<b>且非白名单</b>主机一律不得携带
 *       {@code x-amz-access-token}（{@link SpApiRequestFactory} 在注入 token 前强制校验），
 *       且覆盖生效期间定时任务不调用平台、不落库
 *       （{@code OrderSyncScheduler} / {@code InventorySyncScheduler} 显式跳过，防桩数据污染业务表）。</li>
 * </ol>
 * <p>
 * <b>覆盖的粒度是「主机+基址」而不是「单个 region」</b>：一旦配置 {@code spapi.base-url-override}，
 * 三个分组（NA/EU/FE）都指向该基址（本地桩通常只有一个地址）；未配置时按
 * {@link MarketplaceRegistry#resolveHost(String)} 解析官方主机，未知 region 仍抛
 * {@link UnknownMarketplaceException}（fail-closed）。
 * <p>
 * 配置键（见 {@code application.yml}）：{@code spapi.base-url-override}、
 * {@code spapi.lwa-endpoint-override}、{@code spapi.allowlist}。
 */
@Component
public class SpApiEndpointResolver {

    private static final Logger log = LoggerFactory.getLogger(SpApiEndpointResolver.class);

    /** SP-API 基址覆盖配置键。 */
    public static final String OVERRIDE_PROPERTY = "spapi.base-url-override";

    /** LWA token 端点覆盖配置键。 */
    public static final String LWA_OVERRIDE_PROPERTY = "spapi.lwa-endpoint-override";

    /** 额外主机白名单配置键。 */
    public static final String ALLOWLIST_PROPERTY = "spapi.allowlist";

    /** 生产 profile 名。 */
    public static final String PROD_PROFILE = "prod";

    /** 官方 LWA token 端点（默认值，可被覆盖键替换）。 */
    public static final String DEFAULT_LWA_ENDPOINT = "https://api.amazon.com/auth/o2/token";

    /** 官方 LWA 主机（覆盖为自身时视为未覆盖的等价配置，允许）。 */
    private static final String OFFICIAL_LWA_HOST = "api.amazon.com";

    private final SpApiHostPolicy hostPolicy;
    private final String baseUrlOverride;
    private final String lwaEndpoint;
    private final boolean lwaEndpointOverridden;
    private final boolean production;

    /**
     * Spring 装配：从 {@link SpApiConfig} 读取配置，按激活 profile 决定是否允许覆盖。
     *
     * @throws IllegalStateException              prod profile 下存在非空覆盖键（拒绝启动）
     * @throws SpApiEndpointNotAllowedException   覆盖主机不在白名单内
     */
    @Autowired
    public SpApiEndpointResolver(SpApiConfig config, Environment environment) {
        this(config, isProduction(environment));
    }

    /**
     * 便于单测/显式装配的构造器：生产标记由调用方给出。
     *
     * @param production true 表示生产口径（任何覆盖键非空即抛异常）
     */
    public SpApiEndpointResolver(SpApiConfig config, boolean production) {
        this(config.getBaseUrlOverride(),
                config.getLwaEndpointOverride(),
                isBlank(config.getLwaEndpoint()) ? DEFAULT_LWA_ENDPOINT : config.getLwaEndpoint(),
                config.getAllowlist(),
                production);
    }

    /**
     * 核心构造器（无 Spring 依赖，便于单测直接构造）。
     *
     * @param baseUrlOverride     SP-API 基址覆盖（null/空白 = 使用官方主机）
     * @param lwaEndpointOverride LWA 端点覆盖（null/空白 = 使用 {@code lwaEndpoint}）
     * @param lwaEndpoint         LWA 端点默认值（{@code spapi.lwa-endpoint}）
     * @param extraAllowlist      额外允许主机（{@code spapi.allowlist}，可空）
     * @param production          生产口径
     */
    public SpApiEndpointResolver(String baseUrlOverride,
                                 String lwaEndpointOverride,
                                 String lwaEndpoint,
                                 Collection<String> extraAllowlist,
                                 boolean production) {
        this.production = production;
        this.hostPolicy = SpApiHostPolicy.of(extraAllowlist);
        boolean hasSpOverride = !isBlank(baseUrlOverride);
        boolean hasLwaOverride = !isBlank(lwaEndpointOverride);

        if (production && (hasSpOverride || hasLwaOverride)) {
            throw new IllegalStateException(String.format(
                    "生产环境禁止端点覆盖：%s%s 必须为空。检测到 %s=%s，%s=%s。"
                            + "覆盖会把 SP-API access token 与业务请求发往非官方主机，"
                            + "生产必须直连官方主机；如确为联调环境，请勿使用 prod profile（用 dev/sandbox profile）。",
                    OVERRIDE_PROPERTY, " / " + LWA_OVERRIDE_PROPERTY,
                    OVERRIDE_PROPERTY, hasSpOverride ? baseUrlOverride : "(空)",
                    LWA_OVERRIDE_PROPERTY, hasLwaOverride ? lwaEndpointOverride : "(空)"));
        }

        this.baseUrlOverride = hasSpOverride
                ? requireBaseUrl(baseUrlOverride, OVERRIDE_PROPERTY, false)
                : null;
        this.lwaEndpointOverridden = hasLwaOverride;
        this.lwaEndpoint = hasLwaOverride
                ? requireBaseUrl(lwaEndpointOverride, LWA_OVERRIDE_PROPERTY, true)
                : lwaEndpoint;
    }

    /** 测试/显式装配用：无覆盖的官方口径（非生产）。 */
    public static SpApiEndpointResolver officialOnly() {
        return new SpApiEndpointResolver(null, null, DEFAULT_LWA_ENDPOINT, null, false);
    }

    /**
     * 解析 SP-API 主机与基址（含协议，不含末尾斜杠）。
     *
     * @param region 分组码 NA / EU / FE
     * @return 生效端点；{@link Endpoint#overridden()} 为 true 表示来自覆盖配置
     * @throws UnknownMarketplaceException 未知 region（未开启覆盖时）
     */
    public Endpoint resolve(String region) {
        if (baseUrlOverride != null) {
            return new Endpoint(hostOf(baseUrlOverride), baseUrlOverride, true);
        }
        String host = MarketplaceRegistry.resolveHost(region);
        return new Endpoint(host, "https://" + host, false);
    }

    /** 生效的 LWA token 端点（完整 URL）。 */
    public String lwaEndpoint() {
        return lwaEndpoint;
    }

    /** LWA 端点是否来自覆盖配置。 */
    public boolean lwaEndpointOverridden() {
        return lwaEndpointOverridden;
    }

    /** SP-API 基址覆盖是否生效（定时任务据此跳过落库）。 */
    public boolean isOverrideActive() {
        return baseUrlOverride != null;
    }

    /** 当前生效的 SP-API 基址覆盖；未覆盖时为 null。 */
    public String baseUrlOverride() {
        return baseUrlOverride;
    }

    /** 主机白名单策略（供请求构造点复用同一判定）。 */
    public SpApiHostPolicy hostPolicy() {
        return hostPolicy;
    }

    /** 是否生产口径（构造时按 active profile 判定）。 */
    public boolean isProduction() {
        return production;
    }

    private String requireBaseUrl(String rawUrl, String property, boolean lwa) {
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new SpApiEndpointNotAllowedException(
                    property + " 不是合法 URL：" + rawUrl, null);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SpApiEndpointNotAllowedException(
                    property + " 只允许 http/https（联调桩通常为 http://127.0.0.1:port），实际 scheme="
                            + scheme, uri.getHost());
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SpApiEndpointNotAllowedException(property + " 缺少主机名：" + rawUrl, null);
        }
        if (lwa) {
            // LWA 端点允许指向官方 LWA 主机本身（等价配置）或白名单主机
            String normalized = SpApiHostPolicy.normalizeHost(host);
            if (!OFFICIAL_LWA_HOST.equals(normalized)) {
                hostPolicy.requireAllowed(host, LWA_OVERRIDE_PROPERTY + "=" + rawUrl);
            }
        } else {
            hostPolicy.requireAllowed(host, OVERRIDE_PROPERTY + "=" + rawUrl);
        }
        String normalized = stripTrailingSlash(rawUrl.trim());
        log.warn("[SpApiEndpointResolver] {} 已生效（仅限非生产）：{}。"
                        + "该地址收到的响应属桩数据/联调数据，定时任务在覆盖生效期间不落库；"
                        + "生产启动会拒绝此配置。",
                lwa ? LWA_OVERRIDE_PROPERTY : OVERRIDE_PROPERTY, normalized);
        return normalized;
    }

    private static String hostOf(String baseUrl) {
        return SpApiHostPolicy.normalizeHost(URI.create(baseUrl).getHost());
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isProduction(Environment environment) {
        if (environment == null) {
            return false;
        }
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> PROD_PROFILE.equalsIgnoreCase(profile));
    }

    /**
     * 生效端点。
     *
     * @param host       主机名（不含协议/端口），用于日志与签名作用域
     * @param baseUrl    基址（含协议与端口，不含末尾斜杠）
     * @param overridden 是否来自覆盖配置
     */
    public record Endpoint(String host, String baseUrl, boolean overridden) {
    }
}
