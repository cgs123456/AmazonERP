package com.amz.connector;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * SP-API 出站主机白名单策略（P0-51，fail-closed）。
 * <p>
 * 允许承载 SP-API 请求的主机只有两类：
 * <ol>
 *   <li><b>官方主机</b>：{@link MarketplaceRegistry#hostByRegion()} 登记的三个官方主机
 *       （NA / EU / FE 各一套，字面量只允许出现在 {@link MarketplaceRegistry}）；</li>
 *   <li><b>显式白名单</b>：默认只有回环地址（{@code 127.0.0.1} / {@code localhost} / {@code ::1}），
 *       由 {@code spapi.allowlist} 追加本地桩或受控代理主机。</li>
 * </ol>
 * <p>
 * <b>为什么必须存在：</b>端点覆盖（{@code spapi.base-url-override}）是新增攻击面——一旦允许把
 * 请求发往任意主机，等于把 LWA access token 与业务 payload 交给该主机。因此
 * {@link SpApiRequestFactory#spApi} 在**注入 {@code x-amz-access-token} 之前**调用本策略：
 * 校验不通过即抛 {@link SpApiEndpointNotAllowedException}，请求根本不会被构造、token 不会出网。
 * <p>
 * <b>判定规则（不做自动纠正，避免绕过）：</b>主机名仅做「去首尾空白 → 去 IPv6 方括号 → 转小写」归一，
 * 之后**精确匹配**；不做后缀匹配（防 {@code evil-127.0.0.1.example.com}）、不做通配符、不做 DNS 解析
 * （解析结果可能与字面量不同，且会在校验时引入网络依赖）。
 */
public final class SpApiHostPolicy {

    /** 默认白名单：仅回环地址（本地桩 / 沙箱代理）。 */
    public static final Set<String> DEFAULT_ALLOWLIST = Set.of("127.0.0.1", "localhost", "::1");

    private final Set<String> allowlist;

    private SpApiHostPolicy(Set<String> allowlist) {
        this.allowlist = allowlist;
    }

    /** 仅含默认回环白名单的策略（无额外主机）。 */
    public static SpApiHostPolicy defaults() {
        return new SpApiHostPolicy(normalizeAll(DEFAULT_ALLOWLIST));
    }

    /**
     * 在默认回环白名单之外追加显式主机（{@code spapi.allowlist}）。
     *
     * @param extraAllowlist 额外允许的主机名；null / 空白项被忽略
     */
    public static SpApiHostPolicy of(Collection<String> extraAllowlist) {
        Set<String> merged = new LinkedHashSet<>(normalizeAll(DEFAULT_ALLOWLIST));
        if (extraAllowlist != null) {
            for (String host : extraAllowlist) {
                String normalized = normalizeHost(host);
                if (normalized != null && !normalized.isEmpty()) {
                    merged.add(normalized);
                }
            }
        }
        return new SpApiHostPolicy(Set.copyOf(merged));
    }

    /** 官方主机或白名单主机 → true；null / 空白 → false。 */
    public boolean isAllowed(String host) {
        String normalized = normalizeHost(host);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (officialHosts().contains(normalized)) {
            return true;
        }
        return allowlist.contains(normalized);
    }

    /**
     * 校验主机是否允许承载 SP-API 请求。
     *
     * @param host    待校验主机名
     * @param context 错误消息上下文（如 {@code "endpoint=https://...; caller=orders"}），不得含密钥
     * @return 归一后的主机名
     * @throws SpApiEndpointNotAllowedException 主机既非官方也非白名单
     */
    public String requireAllowed(String host, String context) {
        if (!isAllowed(host)) {
            throw new SpApiEndpointNotAllowedException(
                    "拒绝向非白名单主机发送 SP-API 请求（" + context + "）：host=\"" + host + "\"，"
                            + "官方主机=" + officialHosts() + "，额外白名单=" + allowlist
                            + "；如需本地桩，请显式配置 spapi.allowlist（生产环境禁止覆盖）",
                    host);
        }
        return normalizeHost(host);
    }

    /** 当前生效的额外白名单（不含官方主机，只读快照）。 */
    public Set<String> allowlist() {
        return allowlist;
    }

    /** 官方 SP-API 主机集合（只读）。 */
    public static Set<String> officialHosts() {
        return Set.copyOf(MarketplaceRegistry.hostByRegion().values());
    }

    /** 是否官方 SP-API 主机。 */
    public static boolean isOfficialSpApiHost(String host) {
        String normalized = normalizeHost(host);
        return normalized != null && officialHosts().contains(normalized);
    }

    /** 主机名归一：去首尾空白 → 去 IPv6 方括号 → 转小写。 */
    public static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static Set<String> normalizeAll(Collection<String> hosts) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String host : hosts) {
            String value = normalizeHost(host);
            if (value != null && !value.isEmpty()) {
                normalized.add(value);
            }
        }
        return Set.copyOf(normalized);
    }
}
