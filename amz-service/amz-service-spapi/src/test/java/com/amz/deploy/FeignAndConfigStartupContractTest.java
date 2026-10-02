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
 * Feign 与配置层面的"只在真启动才炸"契约。
 * <p>
 * <b>存在动因（2026-10-02 实测，影子库 HTTP 重测过程中撞出）</b>：
 * {@code mvn clean verify} 1704 例全绿、CI 全绿，但 {@code amz-service-finance}
 * 在 master 上<b>根本起不来</b>：
 *
 * <pre>
 * Caused by: java.lang.IllegalArgumentException:
 *     @RequestMapping annotation not allowed on @FeignClient interfaces
 *   -> bean 'spApiFinanceClient' -> feeDiscrepancyServiceImpl -> feeDiscrepancyController
 * </pre>
 *
 * 现行 Spring Cloud OpenFeign 把"类级 {@code @RequestMapping} 挂在 {@code @FeignClient} 接口上"
 * 变成启动期硬失败；正确写法是 {@code @FeignClient(path = ...)}。这类错误编译期与单测都看不见，
 * 而 {@code runtime-smoke} 那道闸只起 message + gateway 两条腿，于是从闸底下滑过去。
 * <p>
 * 第二条规则同源：{@code spring.data.mongodb.password} 在 Boot 里是 {@code char[]} 类型，
 * 写成 {@code ${MONGO_PASSWORD:}} 时空默认值会让绑定抛
 * {@code NullPointerException: Cannot invoke "java.util.Collection.toArray()" because "c" is null}，
 * 同样是"配置层的小坑 = 服务起不来"。Redis 的 password 是 {@code String}，所以那一族
 * 空默认值无害——本规则只钉实测炸过的 char[] 这一族，不泛化。
 */
@DisplayName("部署契约：Feign 类级注解与 char[] 配置不得让服务起不来")
class FeignAndConfigStartupContractTest {

    private static final Path ROOT = findRepoRoot();

    private static final Pattern FEIGN_CLIENT = Pattern.compile("@FeignClient\\b");
    private static final Pattern INTERFACE_DECL = Pattern.compile("\\binterface\\s+\\w+");
    private static final Pattern PATH_ATTR = Pattern.compile("path\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern REQUEST_MAPPING = Pattern.compile("@RequestMapping\\b");

    /** 修好的三个客户端：路径前缀必须仍然生效，否则从"起不来"变成"运行期 404"，更难发现。 */
    private static final Map<String, String> PREFIXED_CLIENTS = Map.of(
            "amz-service/amz-service-finance/src/main/java/com/amz/client/SpApiFinanceClient.java",
            "/spapi/finance",
            "amz-service/amz-service-finance/src/main/java/com/amz/client/ProcurementCostClient.java",
            "/procurement",
            "amz-service/amz-service-product/src/main/java/com/amz/client/SpapiFeedsClient.java",
            "/spapi/feeds");

    @Test
    void noFeignClientInterfaceCarriesClassLevelRequestMapping() throws IOException {
        Map<String, String> violations = new LinkedHashMap<>();
        List<Path> feignFiles = feignClientFiles();
        // 防空跑：扫描本身没找到足够多的 Feign 客户端时，"零违规"毫无意义。
        assertTrue(feignFiles.size() >= 20,
                "只扫到 " + feignFiles.size() + " 个带 @FeignClient 的文件，扫描逻辑或目录结构已变化");

        for (Path file : feignFiles) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String between = classAnnotationBlock(text);
            if (between != null && REQUEST_MAPPING.matcher(between).find()) {
                violations.put(relativize(file), "类级 @RequestMapping 会让上下文刷新失败");
            }
        }
        assertTrue(violations.isEmpty(),
                "以下 @FeignClient 接口带类级 @RequestMapping，Spring Cloud OpenFeign 会在启动期"
                        + "直接抛 \"@RequestMapping annotation not allowed on @FeignClient interfaces\""
                        + "（应改用 @FeignClient(path = ...)）：" + violations);
    }

    /**
     * 去掉类级 {@code @RequestMapping} 的最坏失败模式是"路径前缀跟着丢了"：服务起得来，
     * 但每次跨服务调用 404，而且被降级兜住后表现为静默的空数据。这条把前缀钉回去。
     */
    @Test
    void eachPrefixedClientDeclaresItsPathOnTheFeignClient() throws IOException {
        for (Map.Entry<String, String> entry : PREFIXED_CLIENTS.entrySet()) {
            Path file = ROOT.resolve(entry.getKey());
            String block = classAnnotationBlock(Files.readString(file, StandardCharsets.UTF_8));
            assertNotNullBlock(file, block);
            Matcher matcher = PATH_ATTR.matcher(block);
            List<String> paths = new ArrayList<>();
            while (matcher.find()) {
                paths.add(matcher.group(1));
            }
            assertTrue(paths.contains(entry.getValue()),
                    entry.getKey() + " 应通过 @FeignClient(path = \"" + entry.getValue()
                            + "\") 保留原来的类级前缀，实际 path=" + paths
                            + "；前缀丢了会变成运行期 404，比启动失败更难发现");
        }
    }

    @Test
    void mongoPasswordIsNeverBoundFromAnEmptyDefault() throws IOException {
        List<String> offenders = new ArrayList<>();
        int mongodbBlocks = 0;
        for (Path yaml : applicationYamls()) {
            List<String> lines = Files.readAllLines(yaml, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).strip().equals("mongodb:")) {
                    continue;
                }
                mongodbBlocks++;
                int blockIndent = indent(lines.get(i));
                for (int j = i + 1; j < lines.size(); j++) {
                    String child = lines.get(j);
                    if (child.isBlank()) {
                        continue;
                    }
                    if (indent(child) <= blockIndent) {
                        break;
                    }
                    String stripped = child.strip().replaceAll("\\s", "");
                    // 只钉 mongodb 这一族：Boot 把它的 password 定为 char[]，
                    // 空默认值会在绑定阶段抛 NPE（实测）。redis/db 的 password 是 String，无此问题。
                    if (stripped.startsWith("password:") && stripped.contains("${")
                            && stripped.endsWith(":}")) {
                        offenders.add(relativize(yaml) + ":" + (j + 1) + " -> " + child.strip());
                    }
                }
            }
        }
        assertTrue(mongodbBlocks >= 1,
                "一个 mongodb: 配置块都没找到——本规则的扫描对象已消失，应连同规则一起调整而不是留一条静默绿的测试");
        assertTrue(offenders.isEmpty(),
                "这些 spring.data.mongodb.password 用了空默认值：MONGO_PASSWORD 未设时"
                        + "char[] 绑定抛 NullPointerException，服务在启动期就起不来：" + offenders);
    }

    private static List<Path> applicationYamls() throws IOException {
        try (Stream<Path> stream = Files.walk(ROOT.resolve("amz-service"))) {
            return stream.filter(path -> path.getFileName().toString().matches("application.*\\.yml"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/target/"))
                    .toList();
        }
    }

    private static int indent(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    /**
     * {@code @FeignClient} 与接口声明之间的注解块——类级注解只可能出现在这里。
     * 返回 null 表示这个文件里有 @FeignClient 但没有接口声明（结构异常，调用方按缺失处理）。
     */
    private static String classAnnotationBlock(String text) {
        Matcher feign = FEIGN_CLIENT.matcher(text);
        if (!feign.find()) {
            return null;
        }
        Matcher declaration = INTERFACE_DECL.matcher(text);
        if (!declaration.find(feign.end())) {
            return null;
        }
        return text.substring(feign.start(), declaration.start());
    }

    private static void assertNotNullBlock(Path file, String block) {
        assertFalse(block == null, file + " 里找不到 @FeignClient 之后的接口声明，扫描逻辑需要更新");
    }

    private static List<Path> feignClientFiles() throws IOException {
        List<Path> found = new ArrayList<>();
        for (String top : List.of("amz-common", "amz-gateway", "amz-service")) {
            Path root = ROOT.resolve(top);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.toString().replace('\\', '/').contains("/target/"))
                        .filter(path -> contains(path, FEIGN_CLIENT))
                        .forEach(found::add);
            }
        }
        return found;
    }

    private static boolean contains(Path path, Pattern pattern) {
        try {
            return pattern.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relativize(Path path) {
        return ROOT.relativize(path).toString().replace('\\', '/');
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
