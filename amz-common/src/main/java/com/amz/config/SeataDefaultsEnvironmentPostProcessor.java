package com.amz.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

/**
 * 把 Seata 的「默认关闭」从注释里的口头承诺变成运行期事实。
 * <p>
 * <b>缺陷（2026-10-09 容器实测定位）</b>：{@code amz-common/src/main/resources/seata-default.yml}
 * 里写着 {@code seata.enabled: ${SEATA_ENABLED:false}}，但这个文件名既不是
 * {@code application*.yml}，全仓也没有任何 {@code spring.config.import} 引用它——它是<b>死配置</b>，
 * 从不参与属性解析。于是 {@code seata.enabled} 在运行期根本不存在，而 Seata 自己的
 * {@code io.seata.spring.boot.autoconfigure.SeataAutoConfiguration} 上是
 * {@code @ConditionalOnProperty(prefix="seata", name="enabled", havingValue="true", matchIfMissing=true)}
 * ——<b>属性缺失即视为启用</b>。结果是每个服务都无条件拉起 GlobalTransactionScanner，
 * 在没有任何 TC 的环境里每 10 秒刷一条
 * {@code ConfigNotFoundException: service.vgroupMapping.default_tx_group configuration item is required}，
 * 与 README「条件启用 SEATA_ENABLED=true」的说法相反。
 * <p>
 * <b>本类的职责</b>：在环境准备阶段把 {@code seata-default.yml} 作为<b>优先级最低</b>的属性源挂进
 * Environment，让那份文件真正生效。因此：
 * <ul>
 *   <li>什么都不配 → {@code seata.enabled=false}（占位符默认值），Seata 自动装配不激活；</li>
 *   <li>显式 {@code SEATA_ENABLED=true}（环境变量）或 yml 里写 {@code seata.enabled: true}
 *       → 命中更高优先级的属性源，Seata 照常启用。</li>
 * </ul>
 * {@code addLast} 是关键：低优先级保证任何显式配置都能覆盖它，而不是被这份默认值反噬。
 * <p>
 * <b>为什么不用「在每个服务的 application.yml 里写 seata.enabled」</b>：那会在 16 份服务配置里
 * 新增 {@code ${SEATA_ENABLED:...}} 占位符，触发 PlaceholderCoverageContractTest 要求的
 * Compose/K8s/.env 三处逐模块同步（坑 28），改动面与回归面都大得多。
 * <p>
 * 与 {@link SeataAutoConfig} 的关系：后者只是给「显式启用」留一个可读的开关点，本类才是真正决定
 * 默认值的那个。两者都指向同一个属性 {@code seata.enabled}。
 * <p>
 * <b>接口选择不能想当然（2026-10-09 容器实测踩到）</b>：Spring Boot 4 同时带着
 * {@code org.springframework.boot.EnvironmentPostProcessor}（新）与
 * {@code org.springframework.boot.env.EnvironmentPostProcessor}（旧），方法签名一模一样。
 * {@code META-INF/spring.factories} 的键只认<b>新</b>接口；实现旧接口时应用启动直接报
 * {@code IllegalArgumentException: Class [...] is not assignable to factory type [...]}。
 */
public class SeataDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 共享默认值的来源文件（amz-common 的 classpath 根）。 */
    public static final String DEFAULTS_LOCATION = "seata-default.yml";

    /** 属性源名（排查启动配置时按这个名字找）。 */
    public static final String PROPERTY_SOURCE_NAME = "amzSeataDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (PropertySource<?> source : loadDefaults()) {
            environment.getPropertySources().addLast(source);
        }
    }

    private List<PropertySource<?>> loadDefaults() {
        Resource resource = new ClassPathResource(DEFAULTS_LOCATION);
        if (!resource.exists()) {
            // 文件被误删时不能静默退回「Seata 默认开启」：那是本类要修的原始缺陷。
            throw new IllegalStateException(
                    "classpath:" + DEFAULTS_LOCATION + " 缺失，Seata 默认关闭的开关无从生效；"
                            + "请恢复该文件或显式设置 seata.enabled。");
        }
        try {
            return new YamlPropertySourceLoader().load(PROPERTY_SOURCE_NAME, resource);
        } catch (IOException e) {
            throw new IllegalStateException("加载 classpath:" + DEFAULTS_LOCATION + " 失败", e);
        }
    }

    /**
     * 排在最后：必须等 ConfigData（application.yml / 环境变量）都进 Environment 之后再 addLast，
     * 否则默认值可能盖住显式配置。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
