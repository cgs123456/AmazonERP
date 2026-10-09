package com.amz.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seata 默认关闭契约（2026-10-09 容器实测抓到的缺陷：默认值只写在死配置里）。
 * <p>
 * 证据类型 E1（自证）：直接驱动 EnvironmentPostProcessor，不起 Spring 上下文、不连 TC。
 * 它证明的是「默认值生效 + 显式配置能覆盖 + 注册键正确」，<b>不</b>证明 Seata Server 可达
 * （那需要真实 TC，当前无凭据/无 TC）。
 */
@DisplayName("Seata 默认关闭契约（默认 false、显式可覆盖、spring.factories 注册键正确）")
class SeataDefaultsContractTest {

    private static final SpringApplication NO_APP = new SpringApplication();

    @Test
    @DisplayName("什么都不配时 seata.enabled=false，且默认值来自 seata-default.yml 而非硬编码")
    void defaultsToDisabledFromTheSharedFile() {
        StandardEnvironment environment = new StandardEnvironment();

        new SeataDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, NO_APP);

        assertEquals("false", environment.getProperty("seata.enabled"),
                "seata.enabled 缺失会让 Seata starter 的 matchIfMissing=true 生效（默认开启）——本断言就是防这个");
        // 同一份 yml 的其它键也必须真的进来了，否则「加载文件」可能只是加载了个空壳
        assertEquals("amz-erp-tx-group", environment.getProperty("seata.tx-service-group"),
                "seata-default.yml 没有真正参与属性解析（死配置回归）");
        assertEquals("default", environment.getProperty("seata.service.vgroup-mapping.amz-erp-tx-group"),
                "vgroup-mapping 默认值缺失");
    }

    @Test
    @DisplayName("显式配置优先级更高：application.yml / SEATA_ENABLED=true 必须能覆盖默认值")
    void explicitConfigurationWinsOverTheDefault() {
        StandardEnvironment environment = new StandardEnvironment();
        // 模拟 application.yml（或环境变量）已经写好了 seata.enabled=true，优先级高于 addLast 的默认源
        environment.getPropertySources().addFirst(new MapPropertySource(
                "applicationConfig", Map.of("seata.enabled", "true")));

        new SeataDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, NO_APP);

        assertEquals("true", environment.getProperty("seata.enabled"),
                "默认值反噬显式配置：addLast 的优先级被写错，Seata 永远开不起来");
    }

    @Test
    @DisplayName("排在最后执行（LOWEST_PRECEDENCE），确保 ConfigData 先落位")
    void runsLastSoConfigDataIsAlreadyPresent() {
        assertEquals(Ordered.LOWEST_PRECEDENCE, new SeataDefaultsEnvironmentPostProcessor().getOrder());
    }

    @Test
    @DisplayName("spring.factories 用 Boot 4 的 EnvironmentPostProcessor 键注册（键名写错=永不加载）")
    void registeredUnderTheBoot4EnvironmentPostProcessorKey() throws Exception {
        String key = "org.springframework.boot.EnvironmentPostProcessor";
        ClassLoader loader = getClass().getClassLoader();
        java.util.Enumeration<URL> urls = loader.getResources("META-INF/spring.factories");
        List<String> values = new java.util.ArrayList<>();
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            Properties properties = new Properties();
            try (InputStream in = url.openStream()) {
                properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String value = properties.getProperty(key);
            if (value != null) {
                values.add(value);
            }
        }
        assertTrue(values.stream().anyMatch(v -> v.contains(SeataDefaultsEnvironmentPostProcessor.class.getName())),
                "amz-common 的 META-INF/spring.factories 未在 " + key + " 下注册 "
                        + SeataDefaultsEnvironmentPostProcessor.class.getName()
                        + "；注册键写错时 Spring 会静默忽略，默认关闭随之失效。已发现=" + values);
        assertNotNull(new SeataDefaultsEnvironmentPostProcessor());
    }

    @Test
    @DisplayName("实现类必须是注册键指向的那个接口（Boot 4 同时存在新旧两个同名接口）")
    void implementsTheInterfaceTheRegistrationKeyResolvesTo() throws Exception {
        // 实测陷阱（2026-10-09 容器实测踩到）：Spring Boot 4 同时带着
        //   org.springframework.boot.EnvironmentPostProcessor（新）
        //   org.springframework.boot.env.EnvironmentPostProcessor（旧）
        // 两个接口，方法签名一模一样。spring.factories 的键只认新接口；实现旧接口时应用启动直接报
        //   IllegalArgumentException: Class [...] is not assignable to factory type [...]
        // ——连环境都准备不了。所以这条断言必须把「注册键解析出的接口」和「实现类」对起来，
        // 而不是只查字符串：只查字符串的那版测试当时是绿的。
        Class<?> factoryType = Class.forName("org.springframework.boot.EnvironmentPostProcessor");
        assertTrue(factoryType.isAssignableFrom(SeataDefaultsEnvironmentPostProcessor.class),
                "实现类不是注册键 " + factoryType.getName() + " 的实例：启动时 SpringFactoriesLoader "
                        + "会直接抛异常，应用连环境都准备不了。当前实现="
                        + java.util.Arrays.toString(SeataDefaultsEnvironmentPostProcessor.class.getInterfaces()));
    }
}
