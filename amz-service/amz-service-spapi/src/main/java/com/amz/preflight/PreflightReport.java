package com.amz.preflight;

import java.time.Instant;
import java.util.List;

/**
 * 单店铺 SP-API 连通性自检报告。
 *
 * <p><b>它回答的唯一问题：</b>「凭证填进去之后，这个店铺现在到底能不能调通 Amazon？」——
 * 并且要能在失败时<b>分层定界</b>（凭证 / LWA 换 token / 平台只读调用），
 * 因为「403」既可能是 refresh_token 失效，也可能是应用没被卖家授权，
 * 两者处理方式完全不同；混在一起报会让运维无从下手。
 *
 * <p><b>诚实边界（务必遵守）：</b>ready=true 只表示<b>当前进程用当前凭证打通了只读链路</b>，
 * 它<b>不是</b>A5 的「联调记录」，也不等于写入类 operation（Feeds/Reports/Messaging/RDT）可用。
 * 报告里 {@code endpointOverridden=true} 表示打的是非官方端点（本地桩/录制代理），
 * 此时即使 ready=true 也只能算 <b>E2</b> 证据，不得写成 E4/E5。
 *
 * @param shopId             被检店铺
 * @param marketplaceId      凭证登记的 marketplace；凭证缺失时为 null
 * @param region             凭证登记的 region；凭证缺失时为 null
 * @param host               本次将实际出站的 SP-API 主机；无法解析时为 null
 * @param endpointOverridden true 表示主机来自 base-url override（非官方端点）
 * @param checkedAt          检查时刻
 * @param ready              全部阶段均为 PASS 才为 true（任何 SKIP 都为 false）
 * @param stages             按执行顺序的阶段结果
 * @param nextAction         下一步该做什么（第一个失败阶段的 remediation）
 */
public record PreflightReport(Long shopId, String marketplaceId, String region, String host,
                              boolean endpointOverridden, Instant checkedAt, boolean ready,
                              List<PreflightStage> stages, String nextAction) {

    /** 阶段一：凭证存在性与结构完整性（不出网）。 */
    public static final String STAGE_CREDENTIAL = "CREDENTIAL";

    /** 阶段二：LWA refresh_token 换取 access_token（真实出网，除非命中有效缓存）。 */
    public static final String STAGE_LWA = "LWA_TOKEN";

    /** 阶段三：平台只读调用（默认 sellers.getMarketplaceParticipations，无 PII、无需 RDT）。 */
    public static final String STAGE_READ_API = "READ_API";

    public PreflightReport {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }
}
