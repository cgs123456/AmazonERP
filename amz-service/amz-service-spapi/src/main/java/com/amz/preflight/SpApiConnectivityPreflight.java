package com.amz.preflight;

import com.amz.auth.LwaTokenManager;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LwaTokenException;
import com.amz.connector.SpApiCallException;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.UnknownMarketplaceException;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.credential.ShopCredentialValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 「有 API 就可以直接用」的落地闸门：单店铺 SP-API 分层连通性自检。
 *
 * <p><b>它要解决的真实痛点：</b>凭证结构校验（{@link ShopCredentialValidator}）
 * 只能证明「字段填全了」，不能证明「Amazon 认这套凭证」；
 * 而一次性 getOrders 自检在 403 时无法区分「refresh_token 失效」与「应用没被卖家授权」。
 * 本类把链路拆成三段，每段独立计时、独立给可操作结论，失败时明确停在相应段：
 * <ol>
 *   <li>{@link PreflightReport#STAGE_CREDENTIAL}——凭证存在且结构完整（不出网）</li>
 *   <li>{@link PreflightReport#STAGE_LWA}——refresh_token 能换出 access_token（真实出网）</li>
 *   <li>{@link PreflightReport#STAGE_READ_API}——只读 operation 能拿到 200（真实出网）</li>
 * </ol>
 *
 * <p><b>失败即停：</b>LWA 失败时不再打平台，避免用无效 token 刷出一串噪音 401，
 * 也避免把「鉴权失败」错误地记成「限流」。
 *
 * <p><b>全 fail-closed：</b>任何阶段没有实测通过，{@code ready} 就是 false。
 * 未执行的阶段记 {@link PreflightStageStatus#SKIP}（不是 PASS），
 * SKIP 的 remediation 必须写明「为什么没跑」；报告不会因为「其余阶段都过了」而给绿灯。
 *
 * <p><b>脱敏：</b>token 只获取不回显；所有异常文本过 {@link ErrorSummary#redact}；
 * 报告不含 client_secret / refresh_token / access_token / PII。
 *
 * <p><b>证据边界：</b>本类不产生联调证据。{@code endpointOverridden=true}（打本地桩或录制代理）
 * 时即便全绿也只是 <b>E2</b>；真实官方端点下全绿是「可以开始联调」的判据，
 * 仍不等于 A5 的沙箱/生产联调记录（那是 runbook 的事）。
 */
@Component
public class SpApiConnectivityPreflight {

    private static final Logger log = LoggerFactory.getLogger(SpApiConnectivityPreflight.class);

    /** 该 shopId 未登记任何凭证。 */
    public static final String CODE_CREDENTIAL_MISSING = "CREDENTIAL_MISSING";

    /** 凭证字段缺失或互相冲突（只报字段名，不报字段值）。 */
    public static final String CODE_CREDENTIAL_INVALID = "CREDENTIAL_INVALID";

    /** 凭证存储本身不可用（DB 不可达 / 解密失败）。 */
    public static final String CODE_CREDENTIAL_STORE_ERROR = "CREDENTIAL_STORE_ERROR";

    /** LWA 抛出了非 LwaTokenException 的运行时异常（属于代码缺陷，不是配置问题）。 */
    public static final String CODE_LWA_UNEXPECTED = "LWA_UNEXPECTED";

    /** 只读探测抛出了未分类异常。 */
    public static final String CODE_READ_UNEXPECTED = "READ_API_UNEXPECTED";

    /** marketplace 未登记在 MarketplaceRegistry。 */
    public static final String CODE_MARKETPLACE_UNKNOWN = "MARKETPLACE_UNKNOWN";

    private final ShopCredentialStore credentialStore;
    private final LwaTokenManager tokenManager;
    private final ConnectivityProbe probe;
    private final SpApiEndpointResolver endpointResolver;

    /**
     * @param probe           只读探测；mock profile 或未装配时为 null，此时 READ_API 记 SKIP
     * @param endpointResolver 端点解析器；为 null 时报告不输出 host / override 标记
     */
    @Autowired
    public SpApiConnectivityPreflight(ShopCredentialStore credentialStore,
                                      LwaTokenManager tokenManager,
                                      @Nullable ConnectivityProbe probe,
                                      @Nullable SpApiEndpointResolver endpointResolver) {
        this.credentialStore = credentialStore;
        this.tokenManager = tokenManager;
        this.probe = probe;
        this.endpointResolver = endpointResolver;
    }

    /**
     * 执行自检（沿用 token 缓存：命中未临近过期的缓存时不重复换 token）。
     */
    public PreflightReport check(Long shopId) {
        return check(shopId, false);
    }

    /**
     * 执行自检。
     *
     * @param shopId             店铺 ID
     * @param forceTokenRefresh  true 时先失效该店铺 token 缓存，强制走一次真实 LWA 交换；
     *                           用于「刚换了 refresh_token，想立刻验证」的场景，
     *                           否则默认复用的旧 token 会让自检给出假绿灯
     * @return 分层报告；任何异常都不会向外抛（自检本身必须永远能返回结果）
     */
    public PreflightReport check(Long shopId, boolean forceTokenRefresh) {
        Instant checkedAt = Instant.now();
        List<PreflightStage> stages = new ArrayList<>();

        ShopCredential credential;
        long t0 = System.nanoTime();
        try {
            credential = credentialStore.get(shopId);
        } catch (RuntimeException e) {
            log.warn("[preflight] 凭证存储不可用，shopId={}，拒绝继续自检", shopId);
            stages.add(PreflightStage.fail(PreflightReport.STAGE_CREDENTIAL, elapsed(t0),
                    CODE_CREDENTIAL_STORE_ERROR, null,
                    "凭证存储不可用（DB 不可达或解密失败）：先修复凭证存储再自检；"
                            + "不得把「读不到凭证」当成「没有凭证」继续往下跑。",
                    ErrorSummary.redact(String.valueOf(e.getMessage()))));
            return finish(shopId, null, stages, checkedAt);
        }

        if (credential == null) {
            stages.add(PreflightStage.fail(PreflightReport.STAGE_CREDENTIAL, elapsed(t0),
                    CODE_CREDENTIAL_MISSING, null,
                    "该 shopId 未登记凭证：先导入 clientId/clientSecret/refreshToken/region/marketplaceId"
                            + "（写入路径见部署 runbook），不要改业务代码。",
                    "shopId=" + shopId));
            return finish(shopId, null, stages, checkedAt);
        }

        List<String> problems = ShopCredentialValidator.validate(credential);
        if (!problems.isEmpty()) {
            stages.add(PreflightStage.fail(PreflightReport.STAGE_CREDENTIAL, elapsed(t0),
                    CODE_CREDENTIAL_INVALID, null,
                    "凭证字段不完整或互相冲突，需补全后重试："
                            + "marketplaceId 必须是已登记值，region 必须与 marketplace 所属区域一致，"
                            + "accessKey/secretKey 不得只填其一。",
                    "字段问题=" + String.join(",", problems)));
            return finish(shopId, credential, stages, checkedAt);
        }

        stages.add(PreflightStage.pass(PreflightReport.STAGE_CREDENTIAL, elapsed(t0),
                "region=" + credential.getRegion() + " marketplaceId=" + credential.getMarketplaceId()
                        + " sellerIdPresent=" + (credential.getSellerId() != null
                        && !credential.getSellerId().isBlank())));

        long t1 = System.nanoTime();
        if (forceTokenRefresh) {
            tokenManager.invalidate(credential);
        }
        try {
            String token = tokenManager.getToken(credential);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("LWA 返回空 access_token（fail-closed，不得继续打平台）");
            }
            stages.add(PreflightStage.pass(PreflightReport.STAGE_LWA, elapsed(t1),
                    "access_token 获取成功（值不进报告与日志）"));
        } catch (LwaTokenException e) {
            log.warn("[preflight] LWA 失败，shopId={}，code={}，platformStatus={}",
                    shopId, e.getCode(), e.getPlatformStatus());
            stages.add(PreflightStage.fail(PreflightReport.STAGE_LWA, elapsed(t1),
                    e.getCode(), e.getPlatformStatus(), lwaRemediation(e),
                    ErrorSummary.redact(e.getMessage())));
            stages.add(PreflightStage.skip(PreflightReport.STAGE_READ_API,
                    "LWA 未通过：不拿无效 token 去打平台（否则只会得到一串噪音 401，"
                            + "并把鉴权失败误记成限流或平台故障）。"));
            return finish(shopId, credential, stages, checkedAt);
        } catch (RuntimeException e) {
            stages.add(PreflightStage.fail(PreflightReport.STAGE_LWA, elapsed(t1),
                    CODE_LWA_UNEXPECTED, null,
                    "LWA 抛出未预期异常：这属于代码/装配缺陷而非凭证问题，"
                            + "请带上本报告的 detail 修代码，不要反复重填凭证。",
                    ErrorSummary.redact(String.valueOf(e.getMessage()))));
            stages.add(PreflightStage.skip(PreflightReport.STAGE_READ_API,
                    "LWA 未通过：不拿无效 token 去打平台。"));
            return finish(shopId, credential, stages, checkedAt);
        }

        long t2 = System.nanoTime();
        if (probe == null) {
            stages.add(PreflightStage.skip(PreflightReport.STAGE_READ_API,
                    "只读探测 Bean 未装配：当前 profile 未启用真实 SP-API 客户端（例如 mock）。"
                            + "这表示「进程没启用真实链路」，不等于「平台连不上」——两者严禁混为一谈。"));
            return finish(shopId, credential, stages, checkedAt);
        }
        try {
            String summary = probe.probe(credential.getShopId(), credential.getMarketplaceId());
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("只读探测返回空摘要（fail-closed，禁止把空结果当成功）");
            }
            stages.add(PreflightStage.pass(PreflightReport.STAGE_READ_API, elapsed(t2), summary));
        } catch (SpApiCallException e) {
            log.warn("[preflight] 只读探测失败，shopId={}，status={}，requestId={}",
                    shopId, e.getPlatformStatus(), e.getRequestId());
            stages.add(PreflightStage.fail(PreflightReport.STAGE_READ_API, elapsed(t2),
                    readErrorCode(e), platformStatusOrNull(e), readRemediation(e),
                    ErrorSummary.redact(e.getMessage())));
        } catch (UnknownMarketplaceException e) {
            stages.add(PreflightStage.fail(PreflightReport.STAGE_READ_API, elapsed(t2),
                    CODE_MARKETPLACE_UNKNOWN, null,
                    "marketplace 未登记在 MarketplaceRegistry：先在注册表补该站点再自检，"
                            + "不要把未知站点静默回退到默认区域。",
                    ErrorSummary.redact(String.valueOf(e.getMessage()))));
        } catch (RuntimeException e) {
            stages.add(PreflightStage.fail(PreflightReport.STAGE_READ_API, elapsed(t2),
                    CODE_READ_UNEXPECTED, null,
                    "只读探测抛出未预期异常：属于代码/装配缺陷，请带 detail 修代码。",
                    ErrorSummary.redact(String.valueOf(e.getMessage()))));
        }
        return finish(shopId, credential, stages, checkedAt);
    }

    private PreflightReport finish(Long shopId, ShopCredential credential,
                                   List<PreflightStage> stages, Instant checkedAt) {
        boolean ready = !stages.isEmpty()
                && stages.stream().allMatch(PreflightStage::passed);
        return new PreflightReport(shopId,
                credential == null ? null : credential.getMarketplaceId(),
                credential == null ? null : credential.getRegion(),
                host(credential),
                endpointResolver != null && endpointResolver.isOverrideActive(),
                checkedAt,
                ready,
                stages,
                nextAction(stages));
    }

    /** 下一步动作：优先第一个失败阶段的 remediation；无失败但有 SKIP 时说清 SKIP 原因。 */
    private static String nextAction(List<PreflightStage> stages) {
        for (PreflightStage stage : stages) {
            if (stage.status() == PreflightStageStatus.FAIL) {
                return stage.remediation() == null ? "阶段 " + stage.name() + " 失败，无 remediation" : stage.remediation();
            }
        }
        for (PreflightStage stage : stages) {
            if (stage.status() == PreflightStageStatus.SKIP) {
                return stage.remediation() == null ? "阶段 " + stage.name() + " 未执行" : stage.remediation();
            }
        }
        return "三段均通过：可按 connector-acceptance runbook 继续只读联调；"
                + "写入类 operation 与 RDT 仍需单独验收，不得据此宣称已联调完成。";
    }

    private String host(ShopCredential credential) {
        if (endpointResolver == null || credential == null || credential.getRegion() == null) {
            return null;
        }
        try {
            return endpointResolver.resolve(credential.getRegion()).host();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String lwaRemediation(LwaTokenException e) {
        if (e.getCode() == null) {
            return "LWA 失败且无错误码：带上本报告的 detail 定位，不要靠重填凭证碰运气。";
        }
        switch (e.getCode()) {
            case LwaTokenException.CODE_AUTH_FAILED:
                return "refresh_token 已失效/被卖家撤销，或 clientId/clientSecret 与 refresh_token 不匹配："
                        + "需卖家重新授权并把新 refresh_token 写回凭证表，然后带 forceTokenRefresh=true 复检。";
            case LwaTokenException.CODE_RATE_LIMITED:
                return "LWA 端点限流（429）：间隔重试，禁止无退避循环；自检不是压测入口。";
            case LwaTokenException.CODE_UPSTREAM_ERROR:
                return "LWA 上游 5xx：平台侧故障，稍后重试并保留 requestId。";
            case LwaTokenException.CODE_INVALID_RESPONSE:
                return "LWA 返回 2xx 但不符合官方 token 契约："
                        + "检查 lwaEndpoint 或覆盖端点是否指向了非官方桩（桩返回体必须与官方契约一致）。";
            case LwaTokenException.CODE_TRANSPORT_ERROR:
                return "连不上 LWA 端点：检查出网、DNS、TLS、代理与 lwaEndpoint（或覆盖端点）配置。";
            default:
                return "未分类 LWA 错误码 " + e.getCode() + "：带 detail 定位后再决定是否重填凭证。";
        }
    }

    private static String readErrorCode(SpApiCallException e) {
        int status = e.getPlatformStatus();
        if (status == 401) {
            return "READ_API_401";
        }
        if (status == 403) {
            return "READ_API_403";
        }
        if (status == 404) {
            return "READ_API_404";
        }
        if (status == 429) {
            return "READ_API_429";
        }
        if (status >= 500) {
            return "READ_API_5XX";
        }
        if (status == -1) {
            return "READ_API_TRANSPORT";
        }
        if (status == 0) {
            return "READ_API_LOCAL";
        }
        return "READ_API_STATUS_" + status;
    }

    private static Integer platformStatusOrNull(SpApiCallException e) {
        int status = e.getPlatformStatus();
        return status <= 0 ? null : status;
    }

    private static String readRemediation(SpApiCallException e) {
        switch (e.getPlatformStatus()) {
            case 401:
                return "平台拒绝该 token：卖家未授权本应用，或 token 与店铺不匹配（跨店串用会 401）。"
                        + "请核对授权关系与凭证归属店铺。";
            case 403:
                return "无该 operation 权限：常见三类原因——(1) 应用未获卖家授权或未通过 AppStore 审核；"
                        + "(2) IAM 角色/ARN 未正确配置或未授予该角色；(3) 该 operation 需 RDT 而未走受限链路。"
                        + "逐项核对，不要用重试掩盖权限缺失。";
            case 404:
                return "路径或资源不存在：核对本仓库登记的官方路径与 operationId 是否与官方快照一致"
                        + "（SpApiPathContractTest 只保证与本地快照一致，不保证快照本身正确）。";
            case 429:
                return "触发官方限流：降低频率并读取 x-amzn-RateLimit-Limit 回填值；"
                        + "沙箱为 5 rps / burst 15，自检必须串行。";
            case -1:
                return "传输层失败（无 HTTP 响应）：检查出网、DNS、TLS、代理与端点 host；"
                        + "沙箱环境常见原因是根本不允许建立 socket。";
            case 0:
                return "本地失败（凭证/请求构造/响应解析）：不可自动重试，需修配置或代码后人工重放。";
            default:
                if (e.getPlatformStatus() >= 500) {
                    return "平台侧 5xx：有界重试并保留 requestId；连续失败按平台故障处理。";
                }
                return "未分类平台状态码 status=" + e.getPlatformStatus() + "：带 requestId 与 detail 定位。";
        }
    }
}
