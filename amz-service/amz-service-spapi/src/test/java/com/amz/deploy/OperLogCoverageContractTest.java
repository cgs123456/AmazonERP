package com.amz.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作审计覆盖契约：敏感域的写端点必须带 {@code @OperLog}。
 * <p>
 * <b>存在动因</b>：{@code @OperLog} 注解此前全仓零使用、切面只写文件、{@code amz_oper_log}
 * 表被当作死表删除——财务凭证、索赔赔付、库存回补、采购审批、店铺凭证这类操作
 * 事后完全无法追责。光建好落库管道不够：只要没有强制，新增的写端点会继续漏标，
 * 覆盖率随时间衰减。本测试把「必须标注」变成门禁。
 * <p>
 * <b>为什么用源码扫描而不是运行时断言</b>：漏标是一个静态属性（注解在不在），
 * 起容器跑一遍端到端既慢又只能覆盖被调到的路径；扫描源码能一次覆盖全部端点，
 * 且新增端点当天就会红。
 * <p>
 * <b>扫描方式</b>：以 Controller 文件为粒度，先定位写端点方法（{@code @PostMapping} /
 * {@code @PutMapping} / {@code @DeleteMapping}），再看它上方的注解块里有没有
 * {@code @OperLog}。方法级判定而不是「文件里有就行」——后者会让「一个文件标了一个、
 * 其余九个漏标」继续绿。
 * <p>
 * <b>变异验证</b>（写本测试时实际注入确认会红）：删掉任一受管端点的 {@code @OperLog} 行
 * → 该条立刻进缺失清单；把扫描改成「文件内任意位置出现 @OperLog 即算覆盖」
 * → 漏标不再被抓到（故这里按方法块判定）。
 */
class OperLogCoverageContractTest {

    /**
     * 受管的敏感域 Controller（相对仓库根）。
     * <p>
     * 只列真需要追责的域：财务流转、库存变动、采购金额、店铺凭证、外部总账推送。
     * 不追求全量——给「搜索历史删除」这类低价值写端点机械加注解只会稀释审计表。
     */
    private static final Map<String, String> MANAGED = new LinkedHashMap<>();

    static {
        MANAGED.put("amz-service/amz-service-finance/src/main/java/com/amz/controller/FinanceController.java",
                "财务凭证");
        MANAGED.put("amz-service/amz-service-finance/src/main/java/com/amz/controller/ReimbursementClaimController.java",
                "财务索赔");
        MANAGED.put("amz-service/amz-service-finance/src/main/java/com/amz/controller/SettlementController.java",
                "结算同步");
        MANAGED.put("amz-service/amz-service-finance/src/main/java/com/amz/controller/FeeDiscrepancyController.java",
                "费用差异");
        MANAGED.put("amz-service/amz-service-procurement/src/main/java/com/amz/controller/ProcurementController.java",
                "采购供应链");
        MANAGED.put("amz-service/amz-service-logistics/src/main/java/com/amz/controller/InboundController.java",
                "入库单");
        MANAGED.put("amz-service/amz-service-logistics/src/main/java/com/amz/controller/OutboundController.java",
                "出库单");
        MANAGED.put("amz-service/amz-service-logistics/src/main/java/com/amz/controller/LogisticsUpgradeController.java",
                "调拨与差异");
        MANAGED.put("amz-service/amz-service-logistics/src/main/java/com/amz/controller/MultiWarehouseController.java",
                "多仓库存");
        MANAGED.put("amz-service/amz-service-logistics/src/main/java/com/amz/controller/WarehouseController.java",
                "仓储库位");
        MANAGED.put("amz-service/amz-service-spapi/src/main/java/com/amz/controller/ShopCredentialController.java",
                "店铺凭证");
    }

    /** 写端点注解：读端点不需要审计（审计表会因此爆炸，且没有追责价值）。 */
    private static final Pattern WRITE_MAPPING = Pattern.compile(
            "@(PostMapping|PutMapping|DeleteMapping)\\b");

    private static final Pattern OPER_LOG = Pattern.compile("@OperLog\\b");

    private static final Path ROOT = findRepoRoot();

    @Test
    @DisplayName("敏感域的写端点必须带 @OperLog（漏标即红）")
    void sensitiveWriteEndpointsAreAudited() throws IOException {
        List<String> missing = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<String, String> entry : MANAGED.entrySet()) {
            Path file = ROOT.resolve(entry.getKey());
            assertTrue(Files.isRegularFile(file),
                    "受管 Controller 不存在（路径变更？）：" + entry.getKey());
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (Endpoint endpoint : writeEndpoints(source)) {
                checked++;
                if (!endpoint.annotated()) {
                    missing.add(entry.getValue() + " | " + entry.getKey() + " | " + endpoint.method());
                }
            }
        }

        assertTrue(checked > 0, "一个写端点都没扫到——扫描逻辑失效了，这不是通过");
        assertTrue(missing.isEmpty(),
                "以下敏感写端点缺少 @OperLog，操作无法追责：\n" + String.join("\n", missing));
    }

    @Test
    @DisplayName("审计落库 SQL 必须指向 amz_user 库（跨库写入约定）")
    void auditInsertTargetsUserSchema() throws IOException {
        Path impl = ROOT.resolve(
                "amz-common/src/main/java/com/amz/service/impl/OperLogServiceImpl.java");
        assertTrue(Files.isRegularFile(impl), "OperLogServiceImpl 不存在");

        String source = Files.readString(impl, StandardCharsets.UTF_8);
        assertTrue(source.contains("amz_user.amz_oper_log"),
                "各服务的 JdbcTemplate 指向自己的业务库，审计必须带库名限定才落得到 amz_user");
        assertFalse(source.contains("throw new"),
                "审计是旁路：写入器不得向外抛异常，否则 DB 抖动会反过来让业务操作失败");
    }

    @Test
    @DisplayName("审计表由 user 服务迁移持有（V1 已 DROP，V3 重建）")
    void auditTableIsMigratedByUserService() throws IOException {
        Path dir = ROOT.resolve("amz-service/amz-service-user/src/main/resources/db/migration");
        assertTrue(Files.isDirectory(dir), "user 服务迁移目录不存在");

        boolean v3Exists = Files.isRegularFile(dir.resolve("V3__oper_log.sql"));
        assertTrue(v3Exists, "缺少 V3__oper_log.sql：审计表无建表路径，落库会静默失败");

        String v2 = Files.readString(dir.resolve("V2__drop_dead_tables.sql"), StandardCharsets.UTF_8);
        assertTrue(v2.contains("DROP TABLE IF EXISTS amz_oper_log;"),
                "V2 曾删除该表；V1 的 checksum 不可变，重建必须走新的 V3 而不是改 V1");
    }

    /**
     * 把 Java 源文件切成「注解块 + 方法签名」粒度：从上一个方法结束到下一个
     * {@code @XxxMapping} 之后的方法体开头。
     * <p>
     * 用空行分段而不是解析 AST：这里只需要判断「某个 Mapping 上方的注解块里有没有
     * @OperLog」，行级切分足够且不会因为引入新语法而失效。
     */
    /**
     * 把 Java 源文件切成「注解块 + 方法签名」粒度。
     * <p>
     * 判定要点：块必须以 <b>方法签名</b> 结尾（{@code public Result<...> xxx(...)}）。
     * 只含 {@code @Autowired} 字段、或块尾不是方法签名的（字段声明、内部类、表达式）
     * 一律不算端点——否则 {@code @Autowired private XxxService service;} 会被
     * 误判成「缺注解的写端点」，门禁从第一天就假红，很快会被人当噪声绕过。
     */
    /**
     * 逐个写端点判定：对每个 {@code @PostMapping/@PutMapping/@DeleteMapping}，
     * 取「它上方最近的那个方法起始行」到它自己之间的区间作为<b>注解区</b>，
     * 只在这个区间里找 {@code @OperLog}。
     * <p>
     * 为什么不用「按行累积分块」：方法签名跨行、方法体里有 {@code return Result.success(...)}
     * 这类含括号的行，都会让分块错位，把字段声明或表达式误判成端点
     * （第一版实现就因此产生 8 条假红——假红的门禁很快会被当噪声绕过）。
     * 以方法起始行为锚点、只在注解区匹配，误判面收窄到「注解区」本身。
     */
    private static List<Endpoint> writeEndpoints(String source) {
        String[] lines = source.split("\n", -1);
        List<Endpoint> endpoints = new ArrayList<>();
        int lastMethodStart = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (isMethodStart(trimmed)) {
                lastMethodStart = i;
                continue;
            }
            Matcher m = WRITE_MAPPING.matcher(trimmed);
            if (!m.find()) {
                continue;
            }
            // 注解区：上一个方法起始行之后（不含）到本 Mapping 行（含）
            int from = (lastMethodStart >= 0) ? lastMethodStart + 1 : 0;
            StringBuilder block = new StringBuilder();
            for (int j = from; j <= i && j < lines.length; j++) {
                block.append(lines[j]).append('\n');
            }
            String name = methodNameAfter(lines, i);
            endpoints.add(new Endpoint(name, OPER_LOG.matcher(block.toString()).find()));
        }
        return endpoints;
    }

    /**
     * 方法起始行：访问修饰符 + 返回类型 + 方法名 + 参数括号。
     * 排除字段声明（无括号）、{@code return Result.success(...)}（以 return 开头）、
     * 以及注解行。
     */
    private static boolean isMethodStart(String trimmed) {
        return (trimmed.startsWith("public ") || trimmed.startsWith("protected ")
                || trimmed.startsWith("private "))
                && METHOD_START.matcher(trimmed).find();
    }

    private static final Pattern METHOD_START =
            Pattern.compile("^[a-z]+\\s+[\\w<>\\[\\].,?\\s]+\\s+\\w+\\s*\\(");

    /** 从 Mapping 行往下找方法名（跨行签名最多容忍 4 行）。 */
    private static String methodNameAfter(String[] lines, int mappingLine) {
        for (int i = mappingLine; i < Math.min(mappingLine + 5, lines.length); i++) {
            String t = lines[i].trim();
            Matcher m = METHOD_NAME.matcher(t);
            if (m.find()) {
                return m.group(1);
            }
        }
        return lines[mappingLine].trim();
    }

    private static final Pattern METHOD_NAME =
            Pattern.compile("^[a-z]+\\s+[\\w<>\\[\\].,?\\s]+\\s+(\\w+)\\s*\\(");

    private record Endpoint(String method, boolean annotated) {}

    /** 只保留含 Mapping 注解的块：其余块（import / 类字段 / getter）不参与审计判定。 */
    private static void addBlock(StringBuilder current, List<String> blocks) {
        String block = current.toString();
        if (block.contains("@PostMapping") || block.contains("@PutMapping")
                || block.contains("@DeleteMapping")) {
            blocks.add(block);
        }
    }

    private static String firstLine(String block) {
        for (String line : block.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(".github/workflows/ci.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
