package com.amz.connector;

import com.amz.connector.ConnectorEvidencePolicy.Assessment;
import com.amz.connector.ConnectorEvidencePolicy.Criterion;
import com.amz.connector.ConnectorEvidencePolicy.Evidence;
import com.amz.connector.ConnectorEvidencePolicy.Level;
import com.amz.client.SpApiOperationCatalog;
import com.amz.client.SpApiOperationSpec;
import com.amz.credential.ConnectorStartupCheck;
import com.amz.credential.ShopCredentialStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接器能力清单（Task 6 / spec §1.9.1 的 A6）。
 * <p>
 * <b>问题：</b>「支持哪些 operation」此前只存在于 README 与开发者记忆里。清单与实现一旦漂移，
 * 前端「已对接 / 未接通」标签、运维排障和验收 runbook 都会拿到错误事实：
 * 未实现的能力在清单里<b>缺字段</b>（前端显示空白，被读成「待配置」），
 * 而实际上它是「根本没有代码」。spec §1.4.1 初始实测出 7 类未实现能力；
 * Notifications、Listings Items、Product Pricing、Catalog Items 与 FBA Inbound
 * 已纳入同一官方 operation 目录，能力清单与统一执行器同源。
 * <p>
 * <b>解决：</b>一张静态能力表 + 一条机器判定：
 * <ul>
 *   <li>{@link #spapiOperations()}：90 条已实现 operation（与 client 包真实调用点同源）；</li>
 *   <li>{@link #spapiEvidence()}：A1–A8 的逐条证据声明，交给
 *       {@link ConnectorEvidencePolicy#evaluate} 出唯一结论，不在这里自行判定。</li>
 * </ul>
 * <p>
 * <b>证据等级口径（易错点，务必保持）：</b>同一条标准的多个组成部分取<b>最弱</b>者。
 * 例如 A1 的「路径」有官方快照 + sha256 锁（E3），但「分页 / 错误分类」只有桩回放（E2），
 * 因此 A1 只能声明 E2——用局部 E3 抬高整体等于把「部分对齐」说成「全部对齐」。
 * A5（以联调记录为准）在无凭证阶段只能是 {@link Level#E0}：联调记录不可伪造，
 * 声明 E1 会把「自证」包装成「有证据」。
 * <p>
 * <b>不含任何机密：</b>只输出 profile 名、布尔开关、凭证<b>条数</b>与脱敏后的自检结果；
 * clientId / clientSecret / refreshToken / accessKey / secretKey / 任何 token 一律不出现。
 * <p>
 * <b>端点路径说明：</b>服务直连路径是 {@code /spapi/connectors}；网关已提供
 * {@code /api/connectors/**} 别名并重写到服务路径。对外调用可用网关别名，但该别名尚未在真实
 * Nacos 服务发现与 Spring Cloud Gateway 运行期做过端到端验证。
 */
@Component
public class ConnectorRegistry {

    /** SP-API 连接器标识（与 runbook §3.1 的 {@code -Connector} 参数一致）。 */
    public static final String SPAPI = "spapi";

    /** 凭证来源：数据库（{@code amz_shop_credential} 密文落库）。 */
    public static final String SOURCE_DB = "db";

    /** 凭证来源：完全没有。 */
    public static final String SOURCE_NONE = "none";

    /** 自检从未执行时 {@code lastResult} 的取值（不伪造「成功」也不伪造时间）。 */
    public static final String LAST_RESULT_NEVER = "NEVER_RUN";

    /**
     * 未实现 operation 的 {@code path} 占位值。
     * <p>
     * <b>为什么不是真实官方路径：</b>{@code SpApiPathContractTest}（P0-54 护栏）扫描
     * {@code src/main/java} 里以官方路径根开头的字符串字面量，断言它们<b>都真实存在于官方模型</b>。
     * 一旦把未实现能力的「将来要调的路径」写进主代码，能力表就变成了源码中的路径字面量，
     * 会被该断言当成「我们在调这个接口」——第 51 轮实测正是因此失败
     * （命中 {@code products/pricing} 与 {@code fba/inbound} 两条路径根下的字面量）。
     * 更危险的是：这两个 API 家族<b>没有官方快照兜底</b>，写进来等于给出一批未经验证、
     * 将来会被直接复制进客户端的字面量。
     * <p>
     * 因此未实现项只保留能力名与 spec 依据；官方路径改写在 {@code note} 里且<b>不带前导斜杠</b>
     * （既是文档，又不会被路径契约扫描当作调用点）。
     */
    public static final String PATH_NOT_IMPLEMENTED = "(未实现：官方路径见 note 与 spec §1.4.1)";

    /**
     * 未取到自检结果时的 outcomeCode。
     * <p>
     * 与平台 HTTP 状态码区分开：本值是「没跑过」，不是「跑过且成功」。
     */
    public static final String OUTCOME_NOT_RUN = "NOT_RUN";

    private final ShopCredentialStore credentialStore;
    private final ConnectorStartupCheck startupCheck;
    private final Environment environment;

    /** 每个连接器最近一次自检结果（进程内，重启即丢；不落库，避免把诊断数据当业务数据）。 */
    private final ConcurrentHashMap<String, SelfTestResult> lastSelfTests = new ConcurrentHashMap<>();

    @Autowired
    public ConnectorRegistry(ShopCredentialStore credentialStore,
                             ConnectorStartupCheck startupCheck,
                             Environment environment) {
        this.credentialStore = credentialStore;
        this.startupCheck = startupCheck;
        this.environment = environment;
    }

    /**
     * operation 实现状态。
     */
    public enum Status {
        /** 仓库内存在真实调用点，且已纳入限流与契约测试。 */
        IMPLEMENTED("已实现"),
        /** 仓库内没有任何调用点；清单必须显式列出，不得留白。 */
        NOT_IMPLEMENTED("未实现");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一条 operation 能力声明。
     *
     * @param id     与限流器 operationId 同名的稳定标识（如 {@code orders.getOrders}）；
     *               未实现的官方能力用 spec §1.4.1 的能力名
     * @param path   已实现：仓库内实际调用的官方路径；未实现：固定占位 {@link #PATH_NOT_IMPLEMENTED}
     *               （不得写真实官方路径，原因见该常量的说明）
     * @param status 实现状态
     * @param note   依据（已实现：调用点所在类；未实现：spec 编号与关键词命中数）
     */
    public record Operation(String id, String path, Status status, String note) {
        public boolean implemented() {
            return status == Status.IMPLEMENTED;
        }
    }

    /**
     * 一次自检结果（脱敏后可直接对外输出）。
     *
     * @param at          执行时刻
     * @param ok          是否成功
     * @param operation   被调用的 operation
     * @param outcomeCode 平台 HTTP 状态码字符串，或 {@code CREDENTIAL_MISSING} /
     *                    {@code UNKNOWN} / {@code NOT_RUN} 等本地码
     * @param detail      已过 {@link ErrorSummary#redact} 的单行诊断文本
     * @param elapsedMs   耗时
     * @param itemCount   返回条目数（只给数量，绝不给订单内容——订单含 PII）
     */
    public record SelfTestResult(Instant at,
                                 boolean ok,
                                 String operation,
                                 String outcomeCode,
                                 String detail,
                                 long elapsedMs,
                                 int itemCount) {
    }

    /**
     * 连接器能力视图（{@code GET /spapi/connectors} 的单个元素）。
     *
     * @param code                连接器标识
     * @param name                展示名
     * @param enabled             本进程内是否已装配可调用实现
     * @param profile             激活 profile（逗号拼接）
     * @param mockActive          mock（离线样例数据）profile 是否激活——为 true 时本清单里
     *                            任何「成功」都不构成平台证据
     * @param credentialSource    {@code db} / {@code none}；env 与 vault 未实现，本实现永不输出
     * @param credentialCount     已加载店铺凭证<b>条数</b>
     * @param implementedCount    已实现 operation 数
     * @param notImplementedCount 显式标注未实现的官方能力数
     * @param operations          完整能力表
     * @param evidenceLevel       整体证据等级（A1–A8 的最弱一环）
     * @param apiReady            是否达到 API-Ready
     * @param reachable           是否与平台真实连通过（A5 ≥ E4）
     * @param displayText         对外唯一合法表述（三种取值之一）
     * @param blockerSummary      未达标标准摘要
     * @param criteria            A1–A8 逐条等级（{@code A1 -> E2}）
     * @param lastCallAt          最近一次自检时刻（null = 从未执行）
     * @param lastResult          最近一次自检结论（{@code SUCCESS} / {@code FAILED} / {@code NEVER_RUN}）
     * @param lastOutcomeCode     最近一次自检的 outcomeCode
     */
    public record Capability(String code,
                             String name,
                             boolean enabled,
                             String profile,
                             boolean mockActive,
                             String credentialSource,
                             int credentialCount,
                             int implementedCount,
                             int notImplementedCount,
                             List<Operation> operations,
                             String evidenceLevel,
                             boolean apiReady,
                             boolean reachable,
                             String displayText,
                             String blockerSummary,
                             Map<String, String> criteria,
                             Instant lastCallAt,
                             String lastResult,
                             String lastOutcomeCode) {
    }

    /**
     * SP-API 能力表（权威清单）。
     * <p>
     * 90 条已实现 operation = 26 条既有客户端调用点 + 64 条统一 operation 目录。
     * 能力清单必须与 client 包真实调用点同源，否则前端与运维会拿到错误事实。
     *
     * @return 不可变列表
     */
    public static List<Operation> spapiOperations() {
        List<Operation> operations = new ArrayList<>();
        // —— 已实现（operationId 与 SpiRateLimiter 的官方配额表一一对应）——
        operations.add(new Operation("orders.getOrders", "/orders/v0/orders",
                Status.IMPLEMENTED, "OrdersClient.fetchOrders"));
        operations.add(new Operation("orders.getOrderItems", "/orders/v0/orders/{orderId}/orderItems",
                Status.IMPLEMENTED, "OrdersClient.fetchOrderItems"));
        operations.add(new Operation("fbaInventory.getInventorySummaries", "/fba/inventory/v1/summaries",
                Status.IMPLEMENTED, "FbaInventoryClient.fetchAllInventory"));
        operations.add(new Operation("feeds.createFeedDocument", "/feeds/2021-06-30/documents",
                Status.IMPLEMENTED, "FeedsClient.createFeedDocument"));
        operations.add(new Operation("feeds.createFeed", "/feeds/2021-06-30/feeds",
                Status.IMPLEMENTED, "FeedsClient.createFeed（variant=JSON_LISTINGS_FEED）"));
        operations.add(new Operation("feeds.getFeed", "/feeds/2021-06-30/feeds/{feedId}",
                Status.IMPLEMENTED, "FeedsClient.getFeedStatus"));
        operations.add(new Operation("feeds.getFeedDocument", "/feeds/2021-06-30/documents/{feedDocumentId}",
                Status.IMPLEMENTED, "FeedsClient.fetchFeedResult"));
        operations.add(new Operation("reports.createReport", "/reports/2021-06-30/reports",
                Status.IMPLEMENTED, "ReportsRealClient.createReport"));
        operations.add(new Operation("reports.getReport", "/reports/2021-06-30/reports/{reportId}",
                Status.IMPLEMENTED, "ReportsRealClient.getReport"));
        operations.add(new Operation("reports.getReportDocument",
                "/reports/2021-06-30/documents/{reportDocumentId}",
                Status.IMPLEMENTED, "ReportsRealClient.downloadDocument"));
        operations.add(new Operation("fees.getMyFeesEstimates", "/products/fees/v0/feesEstimate",
                Status.IMPLEMENTED, "FeesRealClient.estimateFbaFees"));
        operations.add(new Operation("finances.listFinancialEvents", "/finances/v0/financialEvents",
                Status.IMPLEMENTED, "FinancesRealClient.listFinancialEvents"));
        operations.add(new Operation("sellers.getMarketplaceParticipations",
                "/sellers/v1/marketplaceParticipations", Status.IMPLEMENTED,
                "SellersClient.getMarketplaceParticipations"));
        operations.add(new Operation("messaging.getMessagingActionsForOrder",
                "/messaging/v1/orders/{amazonOrderId}", Status.IMPLEMENTED,
                "AmazonMessagingRealClient.getMessagingActionsForOrder"));
        operations.add(new Operation("messaging.GetAttributes",
                "/messaging/v1/orders/{amazonOrderId}/attributes", Status.IMPLEMENTED,
                "AmazonMessagingRealClient.getOrderAttributes"));
        operations.add(new Operation("messaging.confirmCustomizationDetails",
                "/messaging/v1/orders/{amazonOrderId}/messages/confirmCustomizationDetails",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(CONFIRM_CUSTOMIZATION_DETAILS)"));
        operations.add(new Operation("messaging.createConfirmDeliveryDetails",
                "/messaging/v1/orders/{amazonOrderId}/messages/confirmDeliveryDetails",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(CONFIRM_DELIVERY_DETAILS)"));
        operations.add(new Operation("messaging.createLegalDisclosure",
                "/messaging/v1/orders/{amazonOrderId}/messages/legalDisclosure",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(LEGAL_DISCLOSURE)"));
        operations.add(new Operation("messaging.createConfirmOrderDetails",
                "/messaging/v1/orders/{amazonOrderId}/messages/confirmOrderDetails",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(CONFIRM_ORDER_DETAILS)"));
        operations.add(new Operation("messaging.createConfirmServiceDetails",
                "/messaging/v1/orders/{amazonOrderId}/messages/confirmServiceDetails",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(CONFIRM_SERVICE_DETAILS)"));
        operations.add(new Operation("messaging.CreateWarranty",
                "/messaging/v1/orders/{amazonOrderId}/messages/warranty",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(WARRANTY)"));
        operations.add(new Operation("messaging.createDigitalAccessKey",
                "/messaging/v1/orders/{amazonOrderId}/messages/digitalAccessKey",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(DIGITAL_ACCESS_KEY)"));
        operations.add(new Operation("messaging.createUnexpectedProblem",
                "/messaging/v1/orders/{amazonOrderId}/messages/unexpectedProblem",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(UNEXPECTED_PROBLEM)"));
        operations.add(new Operation("messaging.sendInvoice",
                "/messaging/v1/orders/{amazonOrderId}/messages/invoice",
                Status.IMPLEMENTED, "AmazonMessagingRealClient.sendMessage(INVOICE)；官方模型无 usage plan，限流走保守兜底"));
        operations.add(new Operation("uploads.createUploadDestinationForResource",
                "/uploads/2020-11-01/uploadDestinations/{resource}", Status.IMPLEMENTED,
                "AmazonUploadsRealClient.createUploadDestinationForResource"));
        operations.add(new Operation("tokens.createRestrictedDataToken",
                "/tokens/2021-03-01/restrictedDataToken", Status.IMPLEMENTED,
                "TokensClient.requestToken"));

        // —— 剩余 64 条能力：统一来自官方 operation 目录 ——
        for (SpApiOperationSpec spec : SpApiOperationCatalog.operations()) {
            operations.add(new Operation(spec.operationId(), spec.path(),
                    Status.IMPLEMENTED,
                    "SpApiOperationCatalog + SpApiOperationClient（" + spec.family() + "）"));
        }
        return Collections.unmodifiableList(operations);
    }

    /**
     * SP-API 的 A1–A8 证据声明（每条只声明一次，等级取该标准<b>最弱</b>组成部分）。
     * <p>
     * 与 {@link #spapiOperations()} 的维护规则相同：新增或补强证据时必须同步改这里，
     * 否则清单会退化为「人工声明」（spec §1.9.1(1) 的 E0）。
     *
     * @return 证据声明列表
     */
    public static List<Evidence> spapiEvidence() {
        List<Evidence> evidence = new ArrayList<>();
        // A1：路径有官方快照 + sha256 锁（E3），但分页/错误分类只有桩回放（E2）→ 取 E2
        evidence.add(new Evidence(Criterion.A1, Level.E2,
                "SpApiPathContractTest(E3) + LwaTokenExchangeContractTest(E2) + SpApiProtocolStubTest(E2)"));
        // A2：缺凭证显式失败有单测与启动自检，无官方夹具参与 → E1
        evidence.add(new Evidence(Criterion.A2, Level.E1,
                "ConnectorStartupCheckTest + SpapiController 无凭证显式失败路径"));
        // A3：启动自检三种失败面有单测 → E1
        evidence.add(new Evidence(Criterion.A3, Level.E1, "ConnectorStartupCheckTest"));
        // A4：密文落库 + 按 shopId 隔离已实现；轮换/吊销未实现 → E1（部分，未达 E3）
        evidence.add(new Evidence(Criterion.A4, Level.E1,
                "ShopCredentialStore 密文落库 + 按 shopId 隔离；轮换/吊销无实现与测试"));
        // A5：无联调记录。不可伪造，声明 E0。
        evidence.add(new Evidence(Criterion.A5, Level.E0,
                "(无联调记录：未取得 SP-API 凭证，A5 只能由真实联调取证)"));
        // A6：清单与仓库源码关键字一致性由 ConnectorRegistryTest 自证 → E1
        evidence.add(new Evidence(Criterion.A6, Level.E1, "ConnectorRegistryTest（仓库内自证，无官方模型参与）"));
        // A7：Outbox 安全视图、DLQ 与显式重放已有离线/桩测试 → E3；真实 429/5xx 重放仍待联调
        evidence.add(new Evidence(Criterion.A7, Level.E3,
                "SpApiCallOutboxServiceTest + SpApiOutboxReplayExecutorTest + "
                        + "ConnectorControllerOutboxContractTest；真实 429/5xx 重放与回读待联调"));
        // A8：106 条官方 usage plan 与 15 份官方快照双向比对（sha256 锁）→ E3
        evidence.add(new Evidence(Criterion.A8, Level.E3,
                "SpiRateLimiterTest（官方 usage plan 106 条逐项比对 + 快照 sha256）"));
        return Collections.unmodifiableList(evidence);
    }

    /**
     * 全部连接器能力视图（当前只有 SP-API 一个连接器）。
     *
     * @return 不可变列表
     */
    public List<Capability> describeAll() {
        return Collections.singletonList(describe(SPAPI));
    }

    /**
     * 单个连接器能力视图。
     *
     * @param code 连接器标识；未知或 null 返回 null（由调用方决定 404 语义）
     * @return 能力视图；未知 code 返回 null
     */
    public Capability describe(String code) {
        if (!SPAPI.equals(code)) {
            return null;
        }
        ConnectorStartupCheck.StartupState startupState =
                startupCheck == null ? null : startupCheck.getLastState();
        boolean mockActive = Boolean.TRUE.equals(
                ConnectorSelfDescription.of(environment, startupState).get("mockClientsActive"));
        String profile = ConnectorSelfDescription.profile(environment, startupState);

        List<Operation> operations = spapiOperations();
        int implemented = (int) operations.stream().filter(Operation::implemented).count();
        Assessment assessment = ConnectorEvidencePolicy.evaluate(spapiEvidence());

        SelfTestResult last = lastSelfTests.get(SPAPI);
        return new Capability(
                SPAPI,
                "Amazon Selling Partner API",
                true,
                profile,
                mockActive,
                credentialSource(),
                credentialCount(),
                implemented,
                operations.size() - implemented,
                operations,
                assessment.evidenceLevel().name(),
                assessment.apiReady(),
                assessment.reachable(),
                assessment.displayText(),
                assessment.blockerSummary(),
                criteriaLevels(assessment),
                last == null ? null : last.at(),
                last == null ? LAST_RESULT_NEVER : (last.ok() ? "SUCCESS" : "FAILED"),
                last == null ? OUTCOME_NOT_RUN : last.outcomeCode());
    }

    /**
     * 记录一次自检结果（进程内）。
     *
     * @param code   连接器标识
     * @param result 自检结果；null 表示清除记录
     */
    public void recordSelfTest(String code, SelfTestResult result) {
        if (code == null) {
            return;
        }
        if (result == null) {
            lastSelfTests.remove(code);
        } else {
            lastSelfTests.put(code, result);
        }
    }

    /**
     * 最近一次自检结果。
     *
     * @param code 连接器标识
     * @return 结果；从未执行返回 null
     */
    public SelfTestResult lastSelfTest(String code) {
        return code == null ? null : lastSelfTests.get(code);
    }

    /** 凭证来源：有已加载店铺凭证即 {@code db}，否则 {@code none}（env/vault 未实现，永不输出）。 */
    private String credentialSource() {
        return credentialCount() > 0 ? SOURCE_DB : SOURCE_NONE;
    }

    /** 已加载店铺凭证条数；store 缺失时返回 0（不是「未知」——本字段只用于清单展示）。 */
    private int credentialCount() {
        if (credentialStore == null) {
            return 0;
        }
        try {
            return credentialStore.getActiveShopIds().size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A1–A8 逐条等级（缺失项按 E0），用于对外说明「弱在哪里」。 */
    private static Map<String, String> criteriaLevels(Assessment assessment) {
        Map<String, String> levels = new LinkedHashMap<>();
        for (Criterion criterion : Criterion.values()) {
            levels.put(criterion.name(), assessment.levelOf(criterion).name());
        }
        return Collections.unmodifiableMap(levels);
    }
}
