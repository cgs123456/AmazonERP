package org.springframework.boot.logging.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * amz-common 的 logback-spring.xml 必须能被 Spring Boot 真实装配起来，而不是只"看起来配好了"。
 * <p>
 * 背景：仓库里原有的可观测性契约测试只断言 XML 文本包含某个类名，抓不到下面两类真实故障——
 * <ol>
 *   <li>conversionRule 指向不存在的类（曾写成 toolkit 里根本没有的 TraceIdConverter）。
 *       logback 会记录 ERROR status，Spring Boot 3.5 的
 *       {@code LogbackLoggingSystem#reportConfigurationErrorsIfNecessary} 随即抛
 *       {@code IllegalStateException("Logback configuration error detected")}，
 *       16 个服务启动即失败，而文本断言全绿；</li>
 *   <li>traceId 转换器是否真的产出值。{@code LogbackPatternConverter} 的编译期常量是
 *       "TID: N/A"，真值靠 SkyWalking java agent 运行时字节码增强注入，
 *       无 agent 时必须稳定输出该降级值。</li>
 * </ol>
 * 本测试在独立的 {@link LoggerContext} 里装配真实配置文件，因此不会污染同 JVM 内其它测试的日志状态：
 * {@code <springProfile>}／{@code <springProperty>} 的 Environment 由构造器注入，
 * {@code <conversionRule>} 注册在 context 作用域的对象表里。
 * <p>
 * 本类刻意放在 {@code org.springframework.boot.logging.logback} 包下而不是 com.amz：
 * {@code SpringBootJoranConfigurator} 是 package-private 的，只有同包才能直接构造它，
 * 从而在不触碰全局 LoggerContext 的前提下复刻 Boot 的真实装配路径。
 * 把它移回 com.amz 包会退化成"改用 public 的 LogbackLoggingSystem"，那会重置全局日志上下文，
 * 影响同 JVM 内本模块其它测试。
 */
class AmzLogbackConfigContractTest {

    private static final String CONFIG_RESOURCE = "logback-spring.xml";
    private static final String APPLICATION_NAME = "amz-logback-contract";

    /** 无 agent 时 LogbackPatternConverter 的编译期常量，也是文本分支的降级值。 */
    private static final String NO_AGENT_TRACE_TEXT = "TID: N/A";

    /** 不存在的类会走到这条消息；同时覆盖 deprecated 属性名产生的 WARN 噪声。 */
    private static final Pattern BROKEN_STATUS = Pattern.compile(
            "Failed to instantiate|Unable to instantiate|No such|deprecated|ClassNotFound",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("logback-spring.xml 引用的每个类都必须真实存在于运行时 classpath")
    void everyClassReferencedByLogbackConfigIsLoadable() throws Exception {
        String xml = configText();
        assertFalse(xml.contains("TraceIdConverter"),
                "logback-spring.xml 引用了 TraceIdConverter：apm-toolkit-logback-1.x:9.7.0 里没有这个类，"
                        + "装配时会产生 ERROR status 并让 Spring Boot 抛 IllegalStateException，16 个服务全部起不来");

        List<String> classNames = referencedClasses(xml);
        assertFalse(classNames.isEmpty(), "没有从 logback-spring.xml 中解析出任何 class 属性，解析器本身失效了");

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String className : classNames) {
            Class<?> type;
            try {
                // initialize=false：不要触发 TraceIdPatternLogbackLayout 那类静态块往 JVM 全局
                // PatternLayout.defaultConverterMap 注册转换词，否则隔离性就没了
                type = Class.forName(className, false, loader);
            } catch (ClassNotFoundException ex) {
                fail("logback-spring.xml 引用的类在 classpath 上不存在：" + className
                        + "（这是 16 个服务启动即崩的那类故障）", ex);
                return;
            }
            String role = roleOf(xml, className);
            if ("conversionRule".equals(role)) {
                assertTrue(ch.qos.logback.core.pattern.DynamicConverter.class.isAssignableFrom(type),
                        className + " 作为 converterClass 必须继承 DynamicConverter，实际是 " + type);
            }
        }
    }

    @Test
    @DisplayName("文本分支（默认 profile）装配真实配置后不得产生任何 logback ERROR status")
    void textProfileBranchConfiguresWithoutErrorStatus() throws Exception {
        withConsolePatternFromRepository(() -> {
            LoggerContext context = configure(List.of("prod"));
            assertNoBrokenStatus(context);

            PatternLayout layout = new PatternLayout();
            layout.setContext(context);
            layout.setPattern("[%traceId]");
            layout.start();
            String rendered = layout.doLayout(event(context, Level.INFO, "probe", null));
            layout.stop();
            assertEquals("[" + NO_AGENT_TRACE_TEXT + "]", rendered,
                    "%traceId 未解析成 SkyWalking 降级常量。装配出错的 converter 会输出空串，"
                            + "这正是上一次把\"traceId 已修复\"当成事实采信时看不到的信号");

            String line = encodeConsole(context, event(context, Level.ERROR, "boom",
                    new IllegalStateException("synthetic")) );
            assertTrue(line.contains("[" + NO_AGENT_TRACE_TEXT + "]"), "文本控制台缺少降级 traceId：" + line);
            assertTrue(line.contains("\tat "), "文本控制台没有打印异常堆栈（%wEx 未生效）：" + line);
            return null;
        });
    }

    @Test
    @DisplayName("log-json 分支装配真实配置后输出可解析 JSON，并带 service 字段")
    void jsonProfileBranchEmitsParsableJson() throws Exception {
        LoggerContext context = configure(List.of("prod", "log-json"));
        assertNoBrokenStatus(context);

        byte[] encoded = encodeJson(context, event(context, Level.INFO, "probe", null));
        Map<String, Object> parsed = new ObjectMapper()
                .readValue(new String(encoded, StandardCharsets.UTF_8), Map.class);

        assertEquals(APPLICATION_NAME, parsed.get("service"),
                "JSON 顶层 service 字段必须来自 springProperty(spring.application.name)，"
                        + "否则聚合系统无法区分来源服务，实际为：" + parsed.get("service"));
        assertNotNull(parsed.get("@timestamp"), "JSON 缺少时间戳字段：" + parsed.keySet());
        assertEquals("probe", parsed.get("message"));

        assertFalse(parsed.containsKey("traceId"),
                "JSON 不应再通过 pattern provider 输出 traceId：LogbackPatternConverter 增强后的值带 "
                        + "\"TID:\" 前缀，无法直接用于检索。SkyWalking 官方 TraceIdJsonProvider 输出的是干净 id");
        // 无 agent 时 TraceIdJsonProvider.getTracingId() 读不到值，字段被整体省略（实测行为）；
        // 有 agent 时同一路径输出 "TID":"<trace id>"。跨服务串联能力由运行时冒烟证明，不在本 hermetic 测试内。
    }

    @Test
    @DisplayName("两个分支互斥：未开启 log-json 时不得挂上 JSON appender")
    void jsonAppenderOnlyMountsUnderLogJsonProfile() throws Exception {
        for (List<String> profiles : List.of(List.of("prod"), List.of("prod", "log-json"))) {
            LoggerContext context = configure(profiles);
            boolean json = profiles.contains("log-json");
            assertNotNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(json ? "JSON_CONSOLE" : "CONSOLE"),
                    "profile " + profiles + " 下 root logger 应挂 " + (json ? "JSON_CONSOLE" : "CONSOLE"));
            assertTrue(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders()
                            .hasNext(),
                    "profile " + profiles + " 下 root logger 没有挂载任何 appender，日志会静默丢失");
        }
    }

    /** 复刻 LogbackLoggingSystem#configureByResourceUrl 的三步装配，但用独立 context。 */
    private static LoggerContext configure(List<String> profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("contract",
                Map.of("spring.application.name", APPLICATION_NAME)));
        environment.setActiveProfiles(profiles.toArray(new String[0]));

        LoggerContext context = new LoggerContext();
        context.setName("amz-logback-contract-" + String.join("-", profiles));
        // 独立 context 不是 LoggerFactory 创建的，SLF4J provider 不会给它注入 MDCAdapter，
        // 而 LoggingEvent 构造时会读 MDC —— 不补上就 NPE。
        context.setMDCAdapter(new LogbackMDCAdapter());
        SpringBootJoranConfigurator configurator =
                new SpringBootJoranConfigurator(new LoggingInitializationContext(environment));
        configurator.setContext(context);
        try {
            configurator.doConfigure(configUrl());
        } catch (Exception ex) {
            throw new AssertionError("装配 logback-spring.xml 失败，profiles=" + profiles, ex);
        }
        return context;
    }

    private static void assertNoBrokenStatus(LoggerContext context) {
        StatusManager manager = context.getStatusManager();
        List<String> errors = new ArrayList<>();
        for (Status status : manager.getCopyOfStatusList()) {
            if (status.getLevel() == Status.ERROR || BROKEN_STATUS.matcher(status.getMessage() == null
                    ? "" : status.getMessage()).find()) {
                errors.add(status.getLevel() + " " + status.getMessage()
                        + (status.getThrowable() == null ? "" : " / " + status.getThrowable()));
            }
        }
        assertEquals(List.of(), errors,
                "logback 装配产生 ERROR/可疑 status：Spring Boot 3.5 遇到 ERROR 会直接抛 "
                        + "IllegalStateException 让服务起不来");
    }

    private static ILoggingEvent event(LoggerContext context, Level level, String message, Throwable throwable) {
        Logger logger = context.getLogger("com.amz.contract.Probe");
        return new LoggingEvent(AmzLogbackConfigContractTest.class.getName(), logger, level, message,
                throwable, null);
    }

    private static String encodeConsole(LoggerContext context, ILoggingEvent event) {
        var appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        assertNotNull(appender, "缺少 CONSOLE appender");
        var encoder = ((ch.qos.logback.core.ConsoleAppender<ILoggingEvent>) appender).getEncoder();
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private static byte[] encodeJson(LoggerContext context, ILoggingEvent event) {
        var appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("JSON_CONSOLE");
        assertNotNull(appender, "缺少 JSON_CONSOLE appender");
        var encoder = ((ch.qos.logback.core.ConsoleAppender<ILoggingEvent>) appender).getEncoder();
        return encoder.encode(event);
    }

    private static URL configUrl() {
        URL url = Thread.currentThread().getContextClassLoader().getResource(CONFIG_RESOURCE);
        assertNotNull(url, "classpath 上找不到 " + CONFIG_RESOURCE
                + "（服务运行时取的就是这一份，必须与 amz-common/src/main/resources 同步）");
        return url;
    }

    private static String configText() throws IOException {
        try (InputStream in = configUrl().openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 收集 class="..." 与 converterClass="..." 引用的全部 FQCN，以及它们所属元素名。 */
    private static List<String> referencedClasses(String xml) {
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile("<(\\w[\\w.-]*)[^>]*?\\b(?:class|converterClass)=\"([\\w.$]+)\"")
                .matcher(xml);
        while (matcher.find()) {
            names.add(matcher.group(2));
        }
        return names;
    }

    private static String roleOf(String xml, String className) {
        Matcher matcher = Pattern.compile("<(\\w[\\w.-]*)[^>]*?\\b(?:class|converterClass)=\""
                + Pattern.quote(className) + "\"").matcher(xml);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * console-appender.xml 使用 ${CONSOLE_LOG_PATTERN}，而该属性平时由 Boot 的
     * LoggingSystemProperties 写入系统属性。这里从仓库里真实的 application.yml 取值再写入，
     * 目的是让本测试证明"线上那份 pattern 能被编译并渲染"，而不是测试自己造的 pattern。
     */
    private static <T> T withConsolePatternFromRepository(ExecutableSupplier<T> body) throws Exception {
        String pattern = realConsolePattern();
        String previous = System.getProperty("CONSOLE_LOG_PATTERN");
        System.setProperty("CONSOLE_LOG_PATTERN", pattern);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                System.clearProperty("CONSOLE_LOG_PATTERN");
            } else {
                System.setProperty("CONSOLE_LOG_PATTERN", previous);
            }
        }
    }

    private interface ExecutableSupplier<T> {
        T get() throws Exception;
    }

    private static String realConsolePattern() throws IOException {
        Path yml = repoRoot().resolve("amz-gateway").resolve("src/main/resources/application.yml");
        String text = Files.readString(yml, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(?m)^\\s+console:\\s*\"([^\"]+)\"\\s*$").matcher(text);
        assertTrue(matcher.find(), "无法从 " + yml + " 读取 logging.pattern.console");
        String pattern = matcher.group(1);
        assertFalse(matcher.find(), "gateway 的 application.yml 有多个 console pattern，取样点需要更新");
        assertTrue(pattern.contains("%traceId"),
                "gateway 的 console pattern 不含 %traceId，取样点已失效：" + pattern);
        return pattern;
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
