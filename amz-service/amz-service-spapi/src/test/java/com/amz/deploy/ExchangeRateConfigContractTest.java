package com.amz.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汇率兜底表的跨模块一致性。
 * <p>
 * <b>实测发现的缺陷（2026-10-01 审查轮 Medium）</b>：三套重复汇率实现合并为
 * {@code GlobalExchangeRateService} 后，multiplatform 的 application.yml 里仍留着
 * 一整段<b>已无人读取</b>的 {@code platform.exchange-rates}，而且它的 JPY 是 0.045，
 * 与在用的 {@code amz.exchange-rates}（0.046）不一致 —— 合并前两模块兜底值就不一致，
 * 同一笔日元在两个模块会折算成不同 CNY，这类差异正是靠这段死配置继续存活。
 * <p>
 * 各服务自带一份 {@code amz.exchange-rates} 是有意的（每个可部署单元各自默认，
 * 部署时可用环境变量覆盖），因此这里不合并配置，只把"值必须彼此一致"变成构建期约束：
 * 漂移时本测试变红，而不是等财务报表对不上再回溯。
 */
@DisplayName("汇率兜底配置：跨模块值一致，且不得留无人读取的死段")
class ExchangeRateConfigContractTest {

    private static final Path ROOT = repoRoot();

    @Test
    @DisplayName("每个声明 amz.exchange-rates 的模块，币种→汇率表必须逐值相同")
    void fallbackRateTablesAgreeAcrossModules() throws IOException {
        List<String> offenders = new ArrayList<>();
        Map<String, String> reference = null;
        String referenceModule = null;
        int modulesDeclaring = 0;

        for (Path yml : applicationYamls()) {
            String module = yml.getParent().getParent().getParent().getParent().getFileName().toString();
            for (Map<String, Object> root : loadDocuments(yml)) {
                Map<String, Object> amz = asMap(root.get("amz"));
                Map<String, String> rates = toStringMap(amz == null ? null : amz.get("exchange-rates"));
                if (rates == null) {
                    continue;
                }
                modulesDeclaring++;
                assertFalse(rates.isEmpty(), module + " 声明了 exchange-rates 却是空表");
                for (Map.Entry<String, String> e : rates.entrySet()) {
                    double value = Double.parseDouble(e.getValue());
                    assertTrue(value > 0,
                            module + " 的 " + e.getKey() + " 兜底汇率必须为正，实际 " + e.getValue());
                }
                if (reference == null) {
                    reference = rates;
                    referenceModule = module;
                } else if (!reference.equals(rates)) {
                    offenders.add(module + "=" + rates + " 与 " + referenceModule + "=" + reference + " 不一致");
                }
            }
        }

        // 防空跑：只有一处声明时，本测试并没有真正比对任何东西
        assertTrue(modulesDeclaring >= 2,
                "至少要有一个以上模块声明 amz.exchange-rates 才能比对漂移，实际扫描到 "
                        + modulesDeclaring + " 处");
        assertEquals(List.of(), offenders, "汇率兜底表跨模块漂移");
    }

    @Test
    @DisplayName("不得残留 platform.* 死配置段（凭证已改为按店铺读表）")
    void deadPlatformSectionStaysDead() throws IOException {
        Path multiplatform = ROOT.resolve(
                "amz-service/amz-service-multiplatform/src/main/resources/application.yml");
        for (Map<String, Object> root : loadDocuments(multiplatform)) {
            assertFalse(root.containsKey("platform"),
                    "multiplatform 又出现了 platform.* 全局段：凭证按 (shopId, platform) 读表，"
                            + "汇率走 amz.exchange-rates，这段没人读，留着只会与在用的表悄悄分叉");
        }
    }

    private static List<Path> applicationYamls() throws IOException {
        try (var walk = Files.walk(ROOT.resolve("amz-service"), 5)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("application.yml"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/resources/"))
                    .sorted()
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : null;
    }

    private static Map<String, String> toStringMap(Object node) {
        Map<String, Object> raw = asMap(node);
        if (raw == null) {
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        raw.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v).trim()));
        return out;
    }

    /** 部分模块的 application.yml 用 {@code ---} 分段（profile），必须逐段读而不是当成单文档。 */
    private static List<Map<String, Object>> loadDocuments(Path path) throws IOException {
        List<Map<String, Object>> documents = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (Object document : new Yaml().loadAll(reader)) {
                Map<String, Object> asMap = asMap(document);
                if (asMap != null) {
                    documents.add(asMap);
                }
            }
        }
        return documents;
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
