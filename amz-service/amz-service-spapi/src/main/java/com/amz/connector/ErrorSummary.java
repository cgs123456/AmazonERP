package com.amz.connector;

import com.amz.result.ApiError;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * SP-API 失败文本的收敛与脱敏（P0-52a 修复 / P0-53）。
 * <p>
 * <b>背景</b>：SP-API 客户端已把「HTTP status + 平台 body（{@code errors[].code/message}）」
 * 写进异常文本，但边界层有两类信息损失：
 * <ol>
 *   <li>部分 controller 返回固定文案（如 {@code "sync failed"}），把平台错误整条丢弃，
 *       导致接上真实 API 后只能靠人工捞服务日志定位；</li>
 *   <li>部分 controller 直接透出 {@code e.getMessage()}，会把<b>预签名 S3 URL</b>
 *       （{@code X-Amz-Signature} / {@code X-Amz-Credential} 等查询参数）写进 HTTP 响应与错误日志。</li>
 * </ol>
 * 本类提供唯一出口 {@link #of(Throwable)}；另有 {@link #objectPath(String)}，
 * 供「只回对象路径、不拼完整预签名 URL」的诊断出口复用（同一个实现，避免各处手写解析）：
 * <ul>
 *   <li>沿 {@code getCause()} 取<b>最深层根因</b>——{@code OrdersClient} / {@code FbaInventoryClient}
 *       的熔断 fallback 会把平台错误包在 {@code "... degraded (circuit-breaker/exception)"} 里，
 *       只看最外层会再次丢信息；</li>
 *   <li>脱敏（{@link #redact(String)}）：掩掉签名参数、令牌、密钥与口令；</li>
 *   <li>压成单行并截断到 {@link #MAX_LENGTH}，避免超长 body 撑爆响应与日志。</li>
 * </ul>
 * <p>
 * <b>边界（不得过度承诺）</b>：{@link #of(Throwable)} 仍只提供诊断文本；
 * {@link #toApiError(Throwable)} 提供 P0-52a 所需的机器可读字段。两者都不构成 A1–A8 证据；
 * 真实平台错误码与 requestId 仍必须在凭证联调时取证。
 */
public final class ErrorSummary {

    /** 单条错误文本的字符上限（超出即截断，含省略号）。 */
    public static final int MAX_LENGTH = 1000;

    private static final String TRUNCATED = "…";
    private static final String REDACTED = "***";

    /**
     * 需脱敏的键值对：S3 预签名参数、LWA 令牌、AWS 与 LWA 密钥、口令。
     * 值兼容带引号（JSON）与裸值（查询串 / 请求头）两种形态。
     */
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(x-amz-signature|x-amz-credential|x-amz-security-token|x-amz-access-token"
                    + "|authorization|credential|signature|access_token|refresh_token|refreshToken"
                    + "|client_secret|clientSecret|access_key|accessKey|secret_key|secretKey"
                    + "|session_token|sessionToken|password)"
                    + "(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s&,;\"']+)");

    /** {@code Authorization: Bearer xxx} / {@code Basic xxx} 形态。 */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+");

    /**
     * {@code Authorization: <整段值>}：头值本身就是凭证
     * （SigV4 的 {@code Credential=…, Signature=…} 或 {@code Bearer <token>}）。
     * <p>
     * 必须整段掩掉——只掩第一个词会把手里的 token / 签名原样留下
     * （{@code "Authorization: Bearer eyJ…"} → 只掩 {@code Bearer}，JWT 反而留在文本里）。
     */
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?i)\\b(authorization)(\\s*[=:]\\s*)[^\\r\\n]+");

    /** 异常链下钻上限（防御自引用 cause 造成的死循环）。 */
    private static final int MAX_CAUSE_DEPTH = 32;

    private ErrorSummary() {
    }

    /**
     * 把异常链渲染为单行、已脱敏、有长度上限的诊断文本。
     *
     * @param error 任意异常，可为 {@code null}
     * @return 永不为 {@code null} 且永不为空；无法提取任何信息时返回 {@code "unknown error"}
     */
    public static String of(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        Throwable root = rootCause(error);
        String message = firstNonBlank(root.getMessage(), error.getMessage());
        String text = message == null
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + message;
        return normalize(text);
    }

    /**
     * 掩掉签名 / 令牌 / 密钥类键值，其余文本保持不变。
     * <p>
     * 顺序有语义：先整段掩 {@code Authorization} 头值，再掩 {@code key=value} 类键值，
     * 最后掩裸 {@code Bearer/Basic} 令牌——顺序颠倒会漏掉 {@code Authorization: Bearer <token>} 的 token。
     * （{@code status=403}、平台 {@code errors[].code} 等诊断信息必须保留）。
     *
     * @param raw 原始文本，可为 {@code null}
     * @return 脱敏后的文本；入参为 {@code null}/空串时原样返回
     */
    public static String redact(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String masked = AUTHORIZATION_HEADER.matcher(raw).replaceAll("$1$2" + REDACTED);
        masked = SECRET_PAIR.matcher(masked).replaceAll("$1$2" + REDACTED);
        return BEARER.matcher(masked).replaceAll("$1 " + REDACTED);
    }

    /**
     * 对任意文本执行与异常摘要相同的脱敏、单行化和长度限制。
     * 供类型化异常解析平台 {@code errors[].code/message} 时复用，避免各入口出现不同脱敏口径。
     *
     * @param raw 原始文本，可为 {@code null}
     * @return 永不为 {@code null} 或空串；无法提取时返回 {@code "unknown error"}
     */
    public static String sanitize(String raw) {
        return normalize(raw == null ? "" : raw);
    }

    /**
     * 把异常转换为统一的结构化错误载荷（P0-52a）。
     * <p>
     * 优先沿异常链查找 {@link SpApiCallException}，因此即使被 Sentinel/熔断包装，平台字段也不会丢；
     * {@link LocalApiException}、{@link UnknownMarketplaceException} 与
     * {@link IllegalArgumentException} 明确归类为本地错误，避免把请求参数或 URI 问题误报为 Amazon 故障。
     * 其余未知异常保留 {@code UPSTREAM_ERROR}，但平台字段为 {@code null}，不得猜测。
     */
    public static ApiError toApiError(Throwable error) {
        SpApiCallException spApiError = findSpApiError(error);
        if (spApiError != null) {
            Integer status = spApiError.getPlatformStatus() > 0
                    ? spApiError.getPlatformStatus() : null;
            return new ApiError("SPAPI_CALL_FAILED", status,
                    spApiError.getPlatformCode(), spApiError.getPlatformMessage(),
                    spApiError.getRequestId());
        }
        LwaTokenException lwaError = findCause(error, LwaTokenException.class);
        if (lwaError != null) {
            return new ApiError(lwaError.getCode(), lwaError.getPlatformStatus(),
                    lwaError.getPlatformCode(), lwaError.getPlatformMessage(), null);
        }
        LocalApiException localError = findCause(error, LocalApiException.class);
        if (localError != null) {
            return localError(localError.getCode());
        }
        UnknownMarketplaceException marketplaceError = findCause(error, UnknownMarketplaceException.class);
        if (marketplaceError != null) {
            return localError(marketplaceError.code());
        }
        if (findCause(error, IllegalArgumentException.class) != null) {
            return localError(LocalApiException.CODE_INVALID_REQUEST);
        }
        return new ApiError("UPSTREAM_ERROR", null, null, null, null);
    }

    /**
     * 构造不来自上游的稳定本地错误分类，例如缺凭证、缺 marketplaceId。
     * 不得用它把参数校验失败伪装成平台错误。
     */
    public static ApiError localError(String code) {
        return new ApiError(code, null, null, null, null);
    }

    /**
     * 预签名 URL 的<b>安全诊断视图</b>：只取路径部分，丢弃查询串。
     * <p>
     * 预签名 URL 的凭证全在查询串（{@code X-Amz-Signature} / {@code X-Amz-Credential}），
     * 因此凡是「要写进异常文本或日志的『哪个对象』」都必须走本方法，
     * 而不是直接拼接 {@code url}（P0-53）。
     *
     * @param url 完整 URL，可为 {@code null}
     * @return 路径；URL 缺失/空/不可解析时返回占位符，永不返回 {@code null} 或空串
     */
    public static String objectPath(String url) {
        if (url == null || url.isBlank()) {
            return "(no url)";
        }
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isEmpty() ? "(no path)" : path;
        } catch (RuntimeException e) {
            return "(unparseable url)";
        }
    }

    private static String normalize(String text) {
        String collapsed = redact(text).replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return "unknown error";
        }
        if (collapsed.length() <= MAX_LENGTH) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_LENGTH - TRUNCATED.length()) + TRUNCATED;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            current = cause;
        }
        return current;
    }

    private static SpApiCallException findSpApiError(Throwable error) {
        return findCause(error, SpApiCallException.class);
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return null;
            }
            current = cause;
        }
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
