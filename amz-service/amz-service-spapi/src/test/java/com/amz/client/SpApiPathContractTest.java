package com.amz.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-API 路径官方契约测试（P0-54）。
 * <p>
 * 断言方式：把 6 份 Amazon 官方 OpenAPI 模型快照（字节数 + sha256 双重锁定）解析成
 * <b>路径白名单</b>，再正则扫描 {@code src/main/java} 里所有以官方路径根开头的字符串字面量，
 * 要求「源码里的路径 ⊆ 官方模型声明的 paths」。
 * <p>
 * <b>为什么必须这样测（P0-54 的成因）</b>：源码一直把 Reports 写成
 * {@code /reports/2021-09-01/...}，而 SP-API 从未发布过 {@code 2021-09-01} 这个 Reports 版本
 * ——官方模型仓库只有 {@code reports_2020-09-04.md}（废弃指针）与
 * {@code reports_2021-06-30.json}，官方文档对应页面标题也是 {@code Page Not Found}。
 * 因为客户端、桩、验收 runner、runbook 全都写同一个错版本，自写自测（E1）永远绿灯，
 * 只有拿官方模型做外部期望值才能发现（E3）。该错误的后果是**所有真实 Reports 调用都会 404**，
 * 结算报表链路在凭证到位当天直接不可用。
 * <p>
 * <b>为什么不能用 HTTP 状态码当证据</b>：取 {@code reports_2021-09-01.json} 会返回
 * {@code 200} 之外的 14 字节 {@code 404: Not Found} 体，而官方文档站对不存在的版本
 * 仍返回 {@code 200}（SPA 外壳，标题才是 {@code Page Not Found}）。故本类只认「解析出的
 * {@code paths} 键名」，从不做裸字符串/状态码判定。
 * <p>
 * 证据边界：本测试证明「我方请求路径与官方模型一致」（E3），<b>不</b>证明平台接受我方请求
 * （A5 需凭证联调，见 spec §1.9.1 与 {@code docs/superpowers/runbooks/connector-acceptance-runbook.md}）。
 */
@DisplayName("SP-API 路径与官方模型契约（P0-54）")
class SpApiPathContractTest {

    /** 官方模型快照：classpath 资源 + 字节数 + sha256（来源见 contracts/README.md）。 */
    private record Snapshot(String resource, long bytes, String sha256) {
    }

    private static final List<Snapshot> SNAPSHOTS = List.of(
            new Snapshot("/contracts/reports_2021-06-30.json", 83685L,
                    "d72db9e5280262a92933a0e45e2207c150272f1d66177b2517c20671d69c732c"),
            new Snapshot("/contracts/feeds_2021-06-30.json", 55901L,
                    "ab235b4a0e5ce21083b885dd4f2b8cae7a6f597d7adf2647b47b90d6f5098a16"),
            new Snapshot("/contracts/ordersV0.json", 226555L,
                    "027ac6f5c97126647c6925db9be09f78c7c741cd1d8727a5367374a1846bedc5"),
            new Snapshot("/contracts/productFeesV0.json", 49426L,
                    "d06ad35f909d8c0985845f21420c1f75599531465b27f46d4946f7d7f522fc35"),
            new Snapshot("/contracts/financesV0.json", 134109L,
                    "d80e881091367b0eccd4bde3ce834ed08877d3cf51095239eb8b1e328c0d19d6"),
            new Snapshot("/contracts/fbaInventory.json", 36985L,
                    "7c14bcdb22de8ca2df45e5a40f2a422cff344d45985a68b9515b2e800edcc5ab"));

    /** 我方向平台发起请求的路径根（源码里以这些前缀开头的字符串字面量都要被核对）。 */
    private static final List<String> PATH_ROOTS =
            List.of("/orders/", "/reports/", "/feeds/", "/fba/", "/finances/", "/products/");

    /** 未发布的 Reports 版本（P0-54）：任何源码/测试都不得再出现。 */
    private static final String UNPUBLISHED_REPORTS_VERSION = "2021-09-01";

    /** 匹配 Java 普通字符串字面量（含转义序列）。 */
    private static final Pattern STRING_LITERAL =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final String MAIN_SOURCES = "src/main/java";
    private static final String TEST_SOURCES = "src/test/java";

    @Test
    @DisplayName("6 份官方模型快照被字节数 + sha256 锁定（上游漂移必须显式暴露）")
    void officialSnapshotsArePinned() throws Exception {
        for (Snapshot snapshot : SNAPSHOTS) {
            byte[] bytes = readResource(snapshot.resource());
            assertNotNull(bytes, "测试 classpath 缺少官方模型快照：" + snapshot.resource());
            assertTrue(bytes.length > 1000,
                    snapshot.resource() + " 只有 " + bytes.length
                            + " 字节——官方仓库对不存在的文件返回 14 字节 `404: Not Found`，"
                            + "HTTP 200/404 都不构成内容证据");
            assertTrue(snapshot.bytes() == bytes.length,
                    snapshot.resource() + " 字节数漂移：期望 " + snapshot.bytes() + "，实际 " + bytes.length);
            assertTrue(snapshot.sha256().equals(sha256Hex(bytes)),
                    snapshot.resource() + " sha256 漂移：期望 " + snapshot.sha256()
                            + "，实际 " + sha256Hex(bytes));
        }
    }

    @Test
    @DisplayName("源码里每个 SP-API 路径字面量都存在于官方模型（P0-54 回归护栏）")
    void everySourcePathExistsInOfficialModels() throws Exception {
        Set<String> officialPaths = officialPaths();
        assertFalse(officialPaths.isEmpty(), "官方模型未解析出任何 paths");

        Path sources = moduleSubdirectory(MAIN_SOURCES);
        Set<String> sourcePaths = new LinkedHashSet<>();
        for (Path file : javaFiles(sources)) {
            sourcePaths.addAll(extractCandidatePaths(Files.readString(file, StandardCharsets.UTF_8)));
        }

        assertTrue(sourcePaths.size() >= 8,
                "只从源码提取到 " + sourcePaths.size() + " 个路径字面量（≥8 才算扫描有效）：" + sourcePaths);

        List<String> unknown = new ArrayList<>();
        for (String literal : sourcePaths) {
            if (!isCovered(literal, officialPaths)) {
                unknown.add(literal);
            }
        }
        assertTrue(unknown.isEmpty(),
                "源码使用了官方模型未声明的 SP-API 路径（P0-54 同类缺陷）：" + unknown
                        + "；官方模型声明的路径共 " + officialPaths.size() + " 条");
    }

    @Test
    @DisplayName("未发布的 Reports 版本 2021-09-01 不得再出现（P0-54）")
    void unpublishedReportsVersionMustNotReturn() throws Exception {
        assertFalse(officialPaths().stream().anyMatch(p -> p.contains(UNPUBLISHED_REPORTS_VERSION)),
                "官方模型里竟出现了 " + UNPUBLISHED_REPORTS_VERSION + "：快照或上游已变更，需重新核对 P0-54");

        // 本类自身是唯一允许提到该版本号的文件（否则无法断言「不得再出现」），
        // 但它只把版本号当作被禁止的字符串常量使用，绝不用于构造请求路径。
        Path self = moduleSubdirectory(TEST_SOURCES)
                .resolve("com/amz/client/SpApiPathContractTest.java").toAbsolutePath().normalize();

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path root : List.of(moduleSubdirectory(MAIN_SOURCES), moduleSubdirectory(TEST_SOURCES))) {
            for (Path file : javaFiles(root)) {
                if (file.toAbsolutePath().normalize().equals(self)) {
                    continue;
                }
                scanned++;
                if (Files.readString(file, StandardCharsets.UTF_8).contains(UNPUBLISHED_REPORTS_VERSION)) {
                    offenders.add(file.toString());
                }
            }
        }
        assertTrue(scanned >= 20, "扫描到的源码文件过少（" + scanned + "），守卫测试可能失效");
        assertTrue(offenders.isEmpty(),
                "仍在使用未发布的 Reports 版本 " + UNPUBLISHED_REPORTS_VERSION + "：" + offenders);
    }

    @Test
    @DisplayName("Reports 与 Feeds 的版本段与官方模型一致（2021-06-30）")
    void reportsAndFeedsPathsUseThePublishedVersion() throws Exception {
        Set<String> officialPaths = officialPaths();
        assertTrue(officialPaths.contains("/reports/2021-06-30/reports"));
        assertTrue(officialPaths.contains("/reports/2021-06-30/documents/{reportDocumentId}"));
        assertTrue(officialPaths.contains("/feeds/2021-06-30/feeds"));
        assertTrue(officialPaths.contains("/feeds/2021-06-30/documents"));

        String reportsClient =
                Files.readString(moduleSubdirectory(MAIN_SOURCES).resolve("com/amz/client/ReportsRealClient.java"),
                        StandardCharsets.UTF_8);
        assertTrue(reportsClient.contains("\"/reports/2021-06-30/reports\""),
                "ReportsRealClient 的报表路径必须与官方模型一致");
        assertTrue(reportsClient.contains("\"/reports/2021-06-30/documents\""),
                "ReportsRealClient 的文档路径必须与官方模型一致");
    }

    /** 汇总 6 份快照声明的全部 paths（去重）。 */
    private static Set<String> officialPaths() throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        for (Snapshot snapshot : SNAPSHOTS) {
            JsonObject model = JsonParser
                    .parseString(new String(readResource(snapshot.resource()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject declared = model.getAsJsonObject("paths");
            assertNotNull(declared, snapshot.resource() + " 没有 paths 段");
            paths.addAll(declared.keySet());
        }
        return paths;
    }

    /**
     * 路径字面量是否被官方模型覆盖：完全相等，或以 {@code literal + "/"} 为前缀
     * （客户端普遍用 {@code PATH + "/" + id} 拼接，两种写法都算覆盖）。
     */
    private static boolean isCovered(String literal, Set<String> officialPaths) {
        if (officialPaths.contains(literal)) {
            return true;
        }
        String prefix = literal.endsWith("/") ? literal : literal + "/";
        return officialPaths.stream().anyMatch(path -> path.startsWith(prefix));
    }

    /** 从源码字符串字面量里挑出以官方路径根开头的候选路径。 */
    private static Set<String> extractCandidatePaths(String source) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = STRING_LITERAL.matcher(source);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.indexOf('\\') >= 0) {
                continue;
            }
            for (String root : PATH_ROOTS) {
                if (value.startsWith(root)) {
                    found.add(value);
                    break;
                }
            }
        }
        return found;
    }

    private static List<Path> javaFiles(Path root) throws Exception {
        assertTrue(Files.isDirectory(root), "找不到源码目录：" + root.toAbsolutePath());
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(files::add);
            return files;
        }
    }

    /** 目录定位：兼容「模块目录为 cwd」（Maven）与「仓库根为 cwd」两种执行方式。 */
    private static Path moduleSubdirectory(String relative) {
        Path candidate = Path.of(relative);
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = Path.of("amz-service/amz-service-spapi/" + relative);
        assertTrue(Files.isDirectory(candidate),
                "找不到目录 " + relative + "，尝试路径=" + candidate.toAbsolutePath());
        return candidate;
    }

    private static byte[] readResource(String resource) throws Exception {
        try (InputStream in = SpApiPathContractTest.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}