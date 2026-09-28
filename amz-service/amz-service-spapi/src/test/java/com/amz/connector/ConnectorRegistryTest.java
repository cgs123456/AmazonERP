package com.amz.connector;

import com.amz.connector.ConnectorEvidencePolicy.Assessment;
import com.amz.connector.ConnectorEvidencePolicy.Criterion;
import com.amz.connector.ConnectorEvidencePolicy.Evidence;
import com.amz.connector.ConnectorEvidencePolicy.Level;
import com.amz.connector.ConnectorRegistry.Capability;
import com.amz.connector.ConnectorRegistry.Operation;
import com.amz.connector.ConnectorRegistry.SelfTestResult;
import com.amz.connector.ConnectorRegistry.Status;
import com.amz.credential.ConnectorStartupCheck;
import com.amz.credential.ShopCredentialStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 6：连接器能力清单（spec §1.9.1 的 A6——清单必须与实现一致）。
 * <p>
 * 三条核心断言：
 * <ol>
 *   <li><b>双向一致</b>：已实现的 operation 必须在 {@code src/main/java} 里有真实调用点；
 *       标注未实现的官方能力，其关键词在 {@code src/main/java} 必须命中 <b>0</b>
 *       （否则「未实现」是假声明，与 spec §1.4.1 冲突）；</li>
 *   <li><b>证据诚实</b>：A5（以联调记录为准）在无凭证阶段必须声明 E0，
 *       判定结果 {@code apiReady=false}、{@code reachable=false}、
 *       {@code displayText=具备对接能力（未联调）}——不得靠抬高 A5 造出「已接通」；</li>
 *   <li><b>不留白</b>：未实现项必须显式在列且 note 非空，防止前端把「无代码」渲染成「未配置」。</li>
 * </ol>
 * <p>
 * 证据类型 E1（自证：清单与仓库源码关键字扫描同源）。本类不产生 E3/E4/E5，
 * 也不得被引用为 A5 通过的依据。
 */
@DisplayName("Task 6 连接器能力清单（A6：清单与实现一致）")
class ConnectorRegistryTest {

    /** 主代码根目录（surefire 工作目录为模块根目录）。 */
    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** spec §1.4.1 初始关键词（当前未实现能力的判定依据）。 */
    private static final Map<String, String> NOT_IMPLEMENTED_KEYWORDS = notImplementedKeywords();

    private static ConnectorRegistry registryWith(int credentialCount) {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        when(store.getActiveShopIds()).thenReturn(shopIdsOf(credentialCount));
        return new ConnectorRegistry(store, null, null);
    }

    private static Set<Long> shopIdsOf(int credentialCount) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (int i = 1; i <= credentialCount; i++) {
            ids.add((long) i);
        }
        return ids;
    }

    private static Map<String, String> notImplementedKeywords() {
        return Map.of();
    }

    /**
     * 扫描唯一排除项：能力表本身（{@code ConnectorRegistry.java}）。
     * <p>
     * 该文件的职责就是<b>列出</b>这些关键词（含未实现路径与 spec 依据），
     * 把它计入扫描会让「未实现」永远命中自己。排除清单被
     * {@link #notImplementedOperationsHaveNoCallSite()} 逐字锁死，
     * 防止有人靠扩大排除清单让断言假通过。
     */
    private static final Set<String> SCAN_EXCLUSIONS = Set.of("ConnectorRegistry.java");

    private static final Pattern CLIENT_OPERATION_ID = Pattern.compile(
            "\"((?:orders|fbaInventory|feeds|reports|fees|finances|sellers|messaging|uploads|tokens|notifications|listingsItems|productPricing|catalogItems|fbaInbound)\\.[A-Za-z0-9_]+)\"");

    private static String mainSources() {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !SCAN_EXCLUSIONS.contains(path.getFileName().toString()))
                    .map(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> clientOperationIds() {
        Path clientRoot = MAIN_JAVA.resolve("com/amz/client");
        try (Stream<Path> files = Files.walk(clientRoot)) {
            String sources = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
            Matcher matcher = CLIENT_OPERATION_ID.matcher(sources);
            Set<String> ids = new java.util.LinkedHashSet<>();
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("已实现 operation 在 src/main/java 中确有调用点（operationId 字面量可命中）")
    void implementedOperationsHaveRealCallSites() {
        String sources = mainSources();
        List<Operation> implemented = ConnectorRegistry.spapiOperations().stream()
                .filter(Operation::implemented).toList();
        assertEquals(90, implemented.size(), "已实现 operation 数量必须与 client 包真实调用点一致");
        for (Operation operation : implemented) {
            assertTrue(sources.contains("\"" + operation.id() + "\""),
                    "已实现 operation " + operation.id() + " 必须在 src/main/java 中找到同名 operationId 字面量；"
                            + "找不到说明能力表与限流表已漂移");
        }
    }

    @Test
    @DisplayName("已实现清单与 client 包中的 operationId 双向一致")
    void registryMatchesClientCallSitesBidirectionally() {
        Set<String> registryIds = ConnectorRegistry.spapiOperations().stream()
                .filter(Operation::implemented)
                .map(Operation::id)
                .collect(Collectors.toSet());
        Set<String> clientIds = clientOperationIds();
        String clientOnly = String.join(",",
                clientIds.stream().filter(id -> !registryIds.contains(id)).sorted().toList());
        String registryOnly = String.join(",",
                registryIds.stream().filter(id -> !clientIds.contains(id)).sorted().toList());
        assertEquals(clientIds, registryIds,
                "client 包中的 operationId 与能力台账必须双向一致；client-only="
                        + clientOnly + " registry-only=" + registryOnly);
        assertEquals(90, registryIds.size(), "当前源码实际出站 operationId 共 90 条");
    }

    @Test
    @DisplayName("五类剩余能力已全部实现：未实现清单必须为 0")
    void notImplementedOperationsHaveNoCallSite() {
        List<Operation> missing = ConnectorRegistry.spapiOperations().stream()
                .filter(operation -> operation.status() == Status.NOT_IMPLEMENTED).toList();
        assertTrue(missing.isEmpty(),
                "Notifications、Listings Items、Product Pricing、Catalog Items、FBA Inbound 已全部实现，未实现清单必须为 0");
        assertEquals(NOT_IMPLEMENTED_KEYWORDS.keySet(),
                missing.stream().map(Operation::id).collect(Collectors.toSet()),
                "关键词表与未实现清单必须一一对应");
        assertEquals(Set.of("ConnectorRegistry.java"), SCAN_EXCLUSIONS,
                "扫描排除项只允许是能力表本身；扩大排除清单等于让本断言失效");
    }

    @Test
    @DisplayName("未实现项不得留白：note 非空且说明依据")
    void notImplementedOperationsCarryEvidenceNote() {
        for (Operation operation : ConnectorRegistry.spapiOperations()) {
            assertNotNull(operation.note(), operation.id() + " 的 note 不得为 null");
            assertFalse(operation.note().isBlank(), operation.id() + " 的 note 不得为空");
            assertNotNull(operation.path(), operation.id() + " 的 path 不得为 null");
            assertFalse(operation.path().isBlank(), operation.id() + " 的 path 不得为空");
            if (operation.implemented()) {
                assertTrue(operation.path().startsWith("/"),
                        operation.id() + " 已实现，path 必须是官方路径字面量（会被 P0-54 路径契约扫描覆盖）");
            } else {
                assertEquals(ConnectorRegistry.PATH_NOT_IMPLEMENTED, operation.path(),
                        operation.id() + " 未实现，path 必须是占位值：写真实官方路径会污染 P0-54 路径契约扫描");
                assertFalse(operation.note().startsWith("/"),
                        operation.id() + " 的 note 不得以 / 开头（避免再次引入路径字面量）");
            }
        }
    }

    @Test
    @DisplayName("A5 在无凭证阶段只能声明 E0：联调记录不可伪造")
    void a5IsE0WithoutRealIntegration() {
        List<Evidence> evidence = ConnectorRegistry.spapiEvidence();
        long a5Count = evidence.stream().filter(item -> item.criterion() == Criterion.A5).count();
        assertEquals(1, a5Count, "A5 只能声明一次（多次声明会被取最高值，等于绕过上限）");
        Evidence a5 = evidence.stream().filter(item -> item.criterion() == Criterion.A5).findFirst().orElseThrow();
        assertEquals(Level.E0, a5.level(), "A5 在无凭证阶段必须是 E0");
        for (Evidence item : evidence) {
            assertTrue(item.level().rank() <= item.criterion().offlineCeiling().rank(),
                    item.criterion() + " 声明了超过无凭证上限的等级 " + item.level());
        }
    }

    @Test
    @DisplayName("A7 离线/桩证据最高 E3，真实 429/5xx 重放仍需 E4")
    void a7OfflineEvidenceIsE3() {
        Evidence a7 = ConnectorRegistry.spapiEvidence().stream()
                .filter(item -> item.criterion() == Criterion.A7)
                .findFirst().orElseThrow();
        assertEquals(Level.E3, a7.level(), "Outbox/DLQ/重放离线测试只能声明 E3");
        assertEquals(Level.E4, Criterion.A7.requiredLevel(), "真实重放与回读仍是 API-Ready 门槛");
    }

    @Test
    @DisplayName("判定结果：apiReady=false、reachable=false、displayText 只能是「具备对接能力（未联调）」")
    void assessmentStaysHonest() {
        Assessment assessment = ConnectorEvidencePolicy.evaluate(ConnectorRegistry.spapiEvidence());
        assertFalse(assessment.apiReady(), "无联调记录不得标记 API-Ready");
        assertFalse(assessment.reachable(), "未与平台真实连通不得标记 reachable");
        assertEquals("具备对接能力（未联调）", assessment.displayText());
        assertEquals(Level.E0, assessment.evidenceLevel(), "整体等级取 A1–A8 最弱一环（A5=E0）");
        assertTrue(assessment.blockerSummary().contains("A5"), "A5 必须出现在未达标摘要里");
        assertEquals(8, assessment.levels().size(), "A1–A8 逐条等级必须齐全（缺项按 E0，不是省略）");
    }

    @Test
    @DisplayName("能力视图：90 已实现 / 0 未实现 / 未知 code 返回 null / 凭证来源只可能是 db 或 none")
    void capabilityViewIsConsistent() {
        Capability capability = registryWith(0).describe(ConnectorRegistry.SPAPI);
        assertNotNull(capability);
        assertEquals(ConnectorRegistry.SPAPI, capability.code());
        assertEquals(90, capability.implementedCount());
        assertEquals(0, capability.notImplementedCount());
        assertEquals(90, capability.operations().size());
        assertTrue(capability.enabled(), "本进程内 SP-API 连接器已装配");
        assertEquals(ConnectorRegistry.SOURCE_NONE, capability.credentialSource());
        assertEquals(0, capability.credentialCount());
        assertEquals("E0", capability.evidenceLevel());
        assertEquals("具备对接能力（未联调）", capability.displayText());
        assertFalse(capability.apiReady());
        assertEquals(ConnectorRegistry.LAST_RESULT_NEVER, capability.lastResult());
        assertEquals(ConnectorRegistry.OUTCOME_NOT_RUN, capability.lastOutcomeCode());
        assertNull(capability.lastCallAt(), "从未自检时不得伪造时间");

        Capability withCredentials = registryWith(3).describe(ConnectorRegistry.SPAPI);
        assertEquals(ConnectorRegistry.SOURCE_DB, withCredentials.credentialSource());
        assertEquals(3, withCredentials.credentialCount());

        assertNull(registryWith(0).describe("shopify"), "未知连接器必须返回 null，由调用方决定 404");
        assertNull(registryWith(0).describe(null));
    }

    @Test
    @DisplayName("credentialSource 永不输出 env / vault（两种来源均未实现）")
    void credentialSourceNeverClaimsUnimplementedSources() {
        Set<String> allowed = Set.of(ConnectorRegistry.SOURCE_DB, ConnectorRegistry.SOURCE_NONE);
        for (int count : new int[]{0, 1, 5}) {
            Capability capability = registryWith(count).describe(ConnectorRegistry.SPAPI);
            assertTrue(allowed.contains(capability.credentialSource()), capability.credentialSource());
        }
    }

    @Test
    @DisplayName("mock profile 激活时能力视图必须如实上报 mockActive（此时任何成功都不算证据）")
    void mockProfileIsReported() {
        ConnectorStartupCheck startupCheck = mock(ConnectorStartupCheck.class);
        when(startupCheck.getLastState()).thenReturn(
                new ConnectorStartupCheck.StartupState(new String[]{"mock"}, true, false, 0));
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        when(store.getActiveShopIds()).thenReturn(Set.of());
        Capability capability = new ConnectorRegistry(store, startupCheck, null).describe(ConnectorRegistry.SPAPI);
        assertTrue(capability.mockActive(), "mock profile 激活必须如实上报");
        assertEquals("mock", capability.profile());
        assertFalse(capability.apiReady(), "mock 下的『成功』不构成 API-Ready 证据");
    }

    @Test
    @DisplayName("自检结果：记录后可回读，清除后回到 NEVER_RUN")
    void selfTestResultIsRecorded() {
        ConnectorRegistry registry = registryWith(1);
        assertEquals(ConnectorRegistry.LAST_RESULT_NEVER, registry.describe(ConnectorRegistry.SPAPI).lastResult());

        Instant at = Instant.parse("2026-09-24T00:00:00Z");
        registry.recordSelfTest(ConnectorRegistry.SPAPI, new SelfTestResult(
                at, false, "orders.getOrders", "429", "throttled", 12L, 0));
        SelfTestResult stored = registry.lastSelfTest(ConnectorRegistry.SPAPI);
        assertNotNull(stored);
        assertEquals("429", stored.outcomeCode());
        assertEquals(12L, stored.elapsedMs());

        Capability capability = registry.describe(ConnectorRegistry.SPAPI);
        assertEquals("FAILED", capability.lastResult());
        assertEquals("429", capability.lastOutcomeCode());
        assertEquals(at, capability.lastCallAt());

        registry.recordSelfTest(ConnectorRegistry.SPAPI, null);
        assertEquals(ConnectorRegistry.LAST_RESULT_NEVER, registry.describe(ConnectorRegistry.SPAPI).lastResult());
        registry.recordSelfTest(null, new SelfTestResult(at, true, "x", "200", "ok", 1L, 0));
        assertNull(registry.lastSelfTest(null), "null code 必须被忽略");
    }

    @Test
    @DisplayName("能力表不可变（防止运行期被改造成「看起来支持」）")
    void operationsAreImmutable() {
        List<Operation> operations = ConnectorRegistry.spapiOperations();
        assertThrows(UnsupportedOperationException.class, () -> operations.add(
                new Operation("fake", "/fake", Status.IMPLEMENTED, "must fail")));
        List<Evidence> evidence = ConnectorRegistry.spapiEvidence();
        assertThrows(UnsupportedOperationException.class, () -> evidence.add(
                new Evidence(Criterion.A5, Level.E5, "forged")));
    }

    @Test
    @DisplayName("describeAll 只返回已注册连接器，且不含任何凭证字段")
    void describeAllContainsOnlyRegisteredConnectors() {
        List<Capability> all = new ArrayList<>(registryWith(2).describeAll());
        assertEquals(1, all.size());
        assertEquals(ConnectorRegistry.SPAPI, all.get(0).code());
        String rendered = all.get(0).toString() + all.get(0).criteria();
        for (String secret : List.of("clientSecret", "refreshToken", "accessKey", "secretKey",
                "access_token", "Atza|", "AKIA")) {
            assertFalse(rendered.contains(secret), "能力视图不得出现凭证相关字段：" + secret);
        }
    }
}
