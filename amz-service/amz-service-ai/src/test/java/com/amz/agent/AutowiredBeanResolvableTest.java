package com.amz.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Autowired} 注入点必须真的能解析成 Bean。
 *
 * <p><b>为什么需要这条</b>：§S32 把 ErpToolExecutor（{@code @Component}）里的工具方法
 * 按依赖族拆到 7 个新类时，只给新类加了 {@code @Slf4j}，**没有加任何 Spring 注解**；
 * 同时把 {@code ErpToolExecutor} 里的字段改成了 {@code @Autowired}（required=true）。
 *
 * <p>后果是启动级失败：容器里没有这 7 个类型的 Bean，`NoSuchBeanDefinitionException`，
 * AI 服务起不来。而当时的全部 41 条单测都是手工 {@code new ErpAdTools()} 再
 * `ReflectionTestUtils.setField(...)` 注入的——**没有任何一条测试走 Spring 容器**，
 * 所以 `mvn test` 全绿。这个缺陷在仓库里活了整整一天，直到 §S33.19 盘点的
 * 「这些端点谁在调」追到 AdPilot 时，顺手核对 Bean 注册才被发现。
 *
 * <p><b>判据为什么是反射而不是启动上下文</b>：起一个 {@code @SpringBootTest} 需要
 * DB / Redis / Feign 全套依赖，本机与 CI 都不稳定（§S30 已定案 loopback 不修）。
 * 而这里要防的只是一件事：**被注入的类型本身没有任何「成为 Bean」的途径**。
 * 类上有 {@code @Component}/{@code @Service}/{@code @Repository}/{@code @Configuration}
 * 之一，或在某个 {@code @Bean} 方法里被 {@code new} 出来，就满足；都没有就是缺陷。
 * 这是必要条件检查，不是充分条件——它能抓住「忘了加注解」这一类，
 * 抓不住「加了注解但扫描路径不对」。
 *
 * <p>字段来源用反射而非硬编码列表：新增工具类时忘了加注解，这条测试自动覆盖。
 */
@DisplayName("@Autowired 注入点必须是可解析的 Bean")
class AutowiredBeanResolvableTest {

    /** 能被组件扫描直接注册的注解。 */
    private static final List<String> BEAN_ANNOTATIONS = List.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.context.annotation.Configuration");

    @Test
    @DisplayName("ErpToolExecutor 的每个 @Autowired 字段，其类型都必须是 Spring Bean")
    void everyAutowiredToolFieldIsABean() {
        List<String> offenders = new ArrayList<>();

        for (Field field : ErpToolExecutor.class.getDeclaredFields()) {
            if (!field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                continue;
            }
            Class<?> type = field.getType();
            if (isBeanCandidate(type)) {
                continue;
            }
            offenders.add(field.getName() + " : " + type.getSimpleName());
        }

        assertTrue(offenders.isEmpty(),
                "ErpToolExecutor 用 @Autowired(required=true) 注入了这些类型，但它们都不是 Spring Bean——"
                        + "容器启动时会 NoSuchBeanDefinitionException。"
                        + "给类加上 @Component（或提供 @Bean 方法）：" + offenders);
    }

    /**
     * 类型是否能被容器注册。
     *
     * <p>判据刻意宽松：只要类上有四个构造型注解之一即通过。接口/抽象类不在此列——
     * Feign 客户端接口由 {@code @EnableFeignClients} 生成代理，属于另一套机制，
     * 且它们在本类里都是 {@code @Autowired(required = false)}，缺席不致命。
     */
    private static boolean isBeanCandidate(Class<?> type) {
        if (type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            return true;
        }
        for (String annotation : BEAN_ANNOTATIONS) {
            if (hasAnnotationNamed(type, annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotationNamed(Class<?> type, String fqcn) {
        for (java.lang.annotation.Annotation a : type.getAnnotations()) {
            if (a.annotationType().getName().equals(fqcn)) {
                return true;
            }
        }
        return false;
    }
}
