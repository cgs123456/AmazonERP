package com.amz.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-65：Spring 多构造器注入歧义的防复发契约。
 *
 * <p><b>实测发现的缺陷（2026-09-28）</b>：{@code com.amz.client.TokensClient} 与
 * {@code com.amz.connector.RestrictedDataTokenManager} 都是 {@code @Component}
 * （{@code @Profile("!mock")}）且各有 <b>两个构造器、都没有 {@code @Autowired}</b>。
 * Spring 4.3+ 的「单构造器隐式注入」规则在多构造器时不生效，容器退回去找
 * <b>无参构造器</b>，而这两个类没有无参构造器，于是 {@code amz-service-spapi}
 * <b>启动即失败</b>：
 * {@code BeanCreationException: Error creating bean with name 'tokensClient' ...
 * No default constructor found / NoSuchMethodException: TokensClient.<init>()}。
 *
 * <p>为什么单测全绿却没发现：单测是 {@code new TokensClient(gateway, clock)} 手工装配，
 * <b>从不启动 Spring 上下文</b>。这是本项目第二次出现「测试绿 ≠ 服务能起」
 * （第一次是 Flyway 基线缺失导致服务起不来，见 P0-59）。本测试把这条不变量钉死：
 * 任何被 Spring 托管、且有 2 个及以上构造器的类，必须显式标注一个
 * {@code @Autowired}，否则红灯。
 *
 * <p><b>证据类型 E1（自证）</b>：断言对象是本仓库源码文本，不起 Spring 上下文、
 * 不连任何中间件。它证明「多构造器必须有显式注入标注」，<b>不</b>证明服务能启动
 * （那需要真机启动实测，见 SPEC 1.9.6）。
 */
@DisplayName("P0-65 contract: Spring-managed classes with multiple constructors must declare @Autowired")
class SpringConstructorInjectionContractTest {

    /** Spring 会把类纳入容器管理的注解（命中任一即视为受管 Bean 类）。 */
    private static final List<String> STEREOTYPES = List.of(
            "@Component", "@Service", "@Repository", "@Controller", "@RestController", "@Configuration");

    private static final Pattern TYPE_DECLARATION =
            Pattern.compile("(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+)*class\\s+([A-Z]\\w*)");

    @Test
    @DisplayName("every Spring-managed class with 2+ constructors declares @Autowired on one of them")
    void multiConstructorBeansDeclareAutowired() throws IOException {
        List<String> violations = new ArrayList<>();
        int springClasses = 0;
        int multiConstructorClasses = 0;

        for (Path file : mainJavaSources()) {
            String text = read(file);
            if (!isSpringManaged(text)) {
                continue;
            }
            springClasses++;
            String name = primaryClassName(text);
            if (name == null) {
                continue;
            }
            List<Integer> offsets = constructorOffsets(text, name);
            if (offsets.size() < 2) {
                continue;
            }
            if (hasNoArgConstructor(text, name)) {
                // 存在无参构造器：容器会选中它（配合字段注入），不是启动阻塞项。
                // 真正的阻塞项是「多构造器 + 无无参构造器 + 无 @Autowired」。
                continue;
            }
            multiConstructorClasses++;
            boolean anyAutowired = false;
            for (int offset : offsets) {
                if (precededByAutowired(text, offset)) {
                    anyAutowired = true;
                    break;
                }
            }
            if (!anyAutowired) {
                violations.add(relative(file) + " (" + name + ", " + offsets.size() + " constructors)");
            }
        }

        // 防止扫描逻辑退化后「空集合恒绿」：必须真的扫到受管类和多构造器类。
        assertTrue(springClasses > 50, "scanned too few Spring-managed classes: " + springClasses);
        assertTrue(multiConstructorClasses >= 2,
                "scanner no longer detects the known multi-constructor beans: " + multiConstructorClasses);

        assertTrue(violations.isEmpty(),
                "Spring-managed classes with multiple constructors must annotate one with @Autowired, "
                        + "otherwise the container falls back to a no-arg constructor that does not exist "
                        + "and the service fails to start. Violations:" + System.lineSeparator()
                        + String.join(System.lineSeparator(), violations));
    }

    /** 是否存在无参构造器：存在时容器会退回到它，不会启动失败。 */
    private static boolean hasNoArgConstructor(String text, String className) {
        return Pattern.compile("(?m)^\\s*(?:public\\s+|protected\\s+|private\\s+)?" + Pattern.quote(className) + "\\s*\\(\\s*\\)")
                .matcher(text).find();
    }

    private static boolean isSpringManaged(String text) {
        for (String stereotype : STEREOTYPES) {
            if (text.contains(stereotype)) {
                return true;
            }
        }
        return false;
    }

    private static String primaryClassName(String text) {
        Matcher m = TYPE_DECLARATION.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static List<Integer> constructorOffsets(String text, String className) {
        Pattern ctor = Pattern.compile(
                "(?m)^\\s*(?:public\\s+|protected\\s+|private\\s+)?" + Pattern.quote(className) + "\\s*\\(");
        List<Integer> offsets = new ArrayList<>();
        Matcher m = ctor.matcher(text);
        while (m.find()) {
            offsets.add(m.start());
        }
        return offsets;
    }

    /** 构造器声明前 3 个非空行内出现 {@code @Autowired} 即视为已标注。 */
    private static boolean precededByAutowired(String text, int offset) {
        String head = text.substring(0, offset);
        String[] lines = head.split("\\R");
        int checked = 0;
        for (int i = lines.length - 1; i >= 0 && checked < 3; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue;
            }
            checked++;
            if (line.startsWith("@Autowired")) {
                return true;
            }
            if (!line.startsWith("@")) {
                return false;
            }
        }
        return false;
    }

    private static List<Path> mainJavaSources() throws IOException {
        Path root = repoRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            walk.forEach(p -> {
                String normalized = p.toString().replace('\\', '/');
                if (normalized.contains("/target/")) {
                    return;
                }
                if (!normalized.contains("/src/main/java/")) {
                    return;
                }
                if (!normalized.endsWith(".java")) {
                    return;
                }
                files.add(p);
            });
            return files;
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static String relative(Path path) {
        return repoRoot().relativize(path).toString().replace('\\', '/');
    }

    private static Path repoRoot() {
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("docker-compose.yml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("repo root containing docker-compose.yml not found");
    }

}