package com.amz.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reports 字段名官方契约测试（P0-27 / 计划 Task 3）。
 * <p>
 * 断言方式：把 Amazon 官方 OpenAPI 模型快照（{@code contracts/reports_2021-06-30.json}，
 * 字节数 + sha256 双重锁定）作为<b>字段名白名单</b>，再正则扫描
 * {@link ReportsRealClient} 中所有 {@code str(<var>, "<literal>")} 的响应字段读取，
 * 要求「源码读到的字段名 ⊆ 官方模型属性集」。
 * <p>
 * <b>为什么必须这样测</b>：2026-09-24 的实测发现源码读的是 {@code resultDocumentId}，
 * 而官方 {@code Report} 定义里是 {@code reportDocumentId}。两者都"看起来合理"，
 * 任何自写自测（E1）都无法发现——只有把官方模型作为外部期望值才能发现（E3）。
 * 该缺陷的连锁后果是结算报表文档 ID 恒为 null、{@code downloadDocument} 永远拿不到文件，
 * 而报表状态仍被置为 DONE（spec P0-27）。
 * <p>
 * <b>上游文档陷阱（因此禁止裸字符串扫描）</b>：{@code feeds_2021-06-30.json} 的
 * {@code getFeed} 描述文本里写的是 {@code resultDocumentId}，而它真正声明的属性是
 * {@code resultFeedDocumentId}。若用 {@code rawText.contains(...)} 判定，就会把 Amazon
 * 自己的笔误当成合法字段名——这正是 P0-27 的成因。故本类只对「解析出的
 * {@code properties} 键名」做判定，并对该笔误做显式锁定。
 * <p>
 * 证据边界：本测试证明"我方字段名与官方模型一致"（E3），<b>不</b>证明平台接受我方请求
 * （A5 需凭证联调，见 spec §1.9.1）。
 */
@DisplayName("Reports 官方模型字段名契约（P0-27）")
class ReportsFieldContractTest {

    /** 官方模型快照（测试 classpath 根下的 contracts/ 目录）。 */
    private static final String REPORTS_MODEL = "/contracts/reports_2021-06-30.json";
    private static final String FEEDS_MODEL = "/contracts/feeds_2021-06-30.json";

    /** 2026-09-24 实测基准：字节数 + sha256（漂移即失败，见 contracts/README.md）。 */
    private static final long REPORTS_MODEL_BYTES = 83685L;
    private static final String REPORTS_MODEL_SHA256 =
            "d72db9e5280262a92933a0e45e2207c150272f1d66177b2517c20671d69c732c";
    private static final long FEEDS_MODEL_BYTES = 55901L;
    private static final String FEEDS_MODEL_SHA256 =
            "ab235b4a0e5ce21083b885dd4f2b8cae7a6f597d7adf2647b47b90d6f5098a16";

    /** spec P0-27 的旧字段名：官方 schema 从未声明过它，源码里必须消失。 */
    private static final String LEGACY_WRONG_FIELD = "resultDocumentId";

    /** Feeds 结果报告的官方真实属性名（上游描述笔误指向的就是它）。 */
    private static final String FEEDS_RESULT_FIELD = "resultFeedDocumentId";

    /** 匹配 {@code str(x, "field")} / {@code required(x, "field")} 形式（允许空白）。 */
    private static final Pattern RESPONSE_FIELD_READ =
            Pattern.compile("(?:str|required)\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\s*,\\s*\"([A-Za-z0-9_]+)\"\\s*\\)");

    private static final String SOURCE_RELATIVE =
            "src/main/java/com/amz/client/ReportsRealClient.java";

    @Test
    @DisplayName("官方模型快照被字节数 + sha256 锁定（上游漂移必须显式暴露）")
    void officialSnapshotsArePinnedByByteCountAndHash() throws Exception {
        byte[] reports = readResource(REPORTS_MODEL);
        byte[] feeds = readResource(FEEDS_MODEL);

        assertEquals(REPORTS_MODEL_BYTES, reports.length, "Reports 模型字节数漂移");
        assertEquals(REPORTS_MODEL_SHA256, sha256Hex(reports), "Reports 模型 sha256 漂移");
        assertEquals(FEEDS_MODEL_BYTES, feeds.length, "Feeds 模型字节数漂移");
        assertEquals(FEEDS_MODEL_SHA256, sha256Hex(feeds), "Feeds 模型 sha256 漂移");
    }

    @Test
    @DisplayName("ReportsRealClient 读取的每个响应字段都是官方 Report/ReportDocument 属性")
    void everyResponseFieldReadIsDeclaredByOfficialModel() throws Exception {
        JsonObject model = parseModel(REPORTS_MODEL);
        Set<String> officialProperties = new LinkedHashSet<>();
        officialProperties.addAll(definitionProperties(model, "Report"));
        officialProperties.addAll(definitionProperties(model, "ReportDocument"));

        assertTrue(officialProperties.contains("reportId"), "官方 Report 必须含 reportId");
        assertTrue(officialProperties.contains("reportDocumentId"), "官方 Report 必须含 reportDocumentId");
        assertTrue(officialProperties.contains("compressionAlgorithm"),
                "官方 ReportDocument 必须含 compressionAlgorithm");

        String source = readSource();
        Set<String> readFields = new LinkedHashSet<>();
        Matcher matcher = RESPONSE_FIELD_READ.matcher(source);
        while (matcher.find()) {
            readFields.add(matcher.group(1));
        }

        assertFalse(readFields.isEmpty(),
                "未从 ReportsRealClient 扫到任何 str(<var>, \"<field>\") 调用——正则或源码结构已变，测试失效");
        assertTrue(readFields.size() >= 6,
                "扫描到的字段读取少于 6 处，测试可能已失去覆盖：" + readFields);
        assertTrue(readFields.contains("reportDocumentId"),
                "ReportsRealClient 必须读取官方字段 reportDocumentId，当前为：" + readFields);

        Set<String> offenders = new LinkedHashSet<>(readFields);
        offenders.removeAll(officialProperties);
        assertTrue(offenders.isEmpty(),
                "ReportsRealClient 读取了官方模型未声明的字段（P0-27 同类缺陷）：" + offenders
                        + "；官方属性集=" + officialProperties);
    }

    @Test
    @DisplayName("旧字段名只是上游描述里的笔误：任何 schema 属性都不叫它，源码也不得再用")
    void legacyWrongFieldNameIsGone() throws Exception {
        String source = readSource();
        assertFalse(source.contains(LEGACY_WRONG_FIELD),
                "源码仍含官方 schema 未声明的字段名 " + LEGACY_WRONG_FIELD + "（P0-27）");

        JsonObject reportsModel = parseModel(REPORTS_MODEL);
        JsonObject feedsModel = parseModel(FEEDS_MODEL);

        Set<String> declared = new LinkedHashSet<>();
        declared.addAll(declaredPropertyNames(reportsModel));
        declared.addAll(declaredPropertyNames(feedsModel));

        assertFalse(declared.contains(LEGACY_WRONG_FIELD),
                "官方模型把 " + LEGACY_WRONG_FIELD + " 声明成了属性——contracts/README.md 的结论需要重审");
        assertTrue(declared.contains("reportDocumentId"),
                "官方模型必须声明 reportDocumentId（Reports 结果文档字段）");
        assertTrue(declared.contains(FEEDS_RESULT_FIELD),
                "官方模型必须声明 " + FEEDS_RESULT_FIELD + "（Feeds 结果文档字段）");

        // 锁定"上游描述文本用错名"这一事实：它不是字段，但确实是本次缺陷的诱因。
        // 若上游修订了描述，本断言会失败并要求同步修订 contracts/README.md 的对应小节。
        assertTrue(countOccurrencesInStringValues(feedsModel, LEGACY_WRONG_FIELD) > 0,
                "上游 Feeds 快照已不再在描述文本中出现 " + LEGACY_WRONG_FIELD
                        + "——请修订 contracts/README.md 的「上游文档陷阱」一节后调整本断言");
    }

    private static JsonObject parseModel(String resource) throws Exception {
        return JsonParser.parseString(new String(readResource(resource), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static Set<String> definitionProperties(JsonObject model, String definitionName) {
        JsonObject definition = model.getAsJsonObject("definitions").getAsJsonObject(definitionName);
        assertNotNull(definition, "官方模型缺少定义：" + definitionName);
        Set<String> names = new LinkedHashSet<>();
        definition.getAsJsonObject("properties").keySet().forEach(names::add);
        return names;
    }

    /** 递归收集模型中所有 {@code properties} 对象声明的键名（由 schema 决定，与描述文本无关）。 */
    private static Set<String> declaredPropertyNames(JsonElement element) {
        Set<String> names = new LinkedHashSet<>();
        collectDeclaredPropertyNames(element, names);
        return names;
    }

    private static void collectDeclaredPropertyNames(JsonElement element, Set<String> out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject properties = obj.getAsJsonObject("properties");
            if (properties != null) {
                properties.keySet().forEach(out::add);
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                collectDeclaredPropertyNames(entry.getValue(), out);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectDeclaredPropertyNames(child, out);
            }
        }
    }

    /** 统计 needle 在模型所有字符串字面量（即描述/示例文本）中出现的次数。 */
    private static int countOccurrencesInStringValues(JsonElement element, String needle) {
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            int count = 0;
            int idx = value.indexOf(needle);
            while (idx >= 0) {
                count++;
                idx = value.indexOf(needle, idx + needle.length());
            }
            return count;
        }
        if (element.isJsonObject()) {
            int total = 0;
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                total += countOccurrencesInStringValues(entry.getValue(), needle);
            }
            return total;
        }
        if (element.isJsonArray()) {
            int total = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                total += countOccurrencesInStringValues(child, needle);
            }
            return total;
        }
        return 0;
    }

    private static byte[] readResource(String resource) throws Exception {
        try (InputStream in = ReportsFieldContractTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 缺少官方模型快照：" + resource);
            return in.readAllBytes();
        }
    }

    /** 源码文件定位：兼容「模块目录为 cwd」（Maven）与「仓库根为 cwd」两种执行方式。 */
    private static String readSource() throws Exception {
        Path candidate = Path.of(SOURCE_RELATIVE);
        if (!Files.isRegularFile(candidate)) {
            candidate = Path.of("amz-service/amz-service-spapi/" + SOURCE_RELATIVE);
        }
        assertTrue(Files.isRegularFile(candidate),
                "找不到 ReportsRealClient 源码，尝试路径=" + candidate.toAbsolutePath());
        return Files.readString(candidate, StandardCharsets.UTF_8);
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