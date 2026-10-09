package com.amz.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Seata 分布式事务的显式开关点。
 * <p>
 * 默认值由 {@link SeataDefaultsEnvironmentPostProcessor} 提供（它把
 * {@code amz-common/seata-default.yml} 作为最低优先级属性源挂进 Environment），
 * 因此 {@code seata.enabled} 在运行期恒有取值：
 * <ul>
 *   <li>不配置 → {@code false}，Seata 自动装配（GlobalTransactionScanner/TC 连接）不激活；</li>
 *   <li>{@code SEATA_ENABLED=true}（或 yml 里 {@code seata.enabled: true}）→ 本类与
 *       {@code io.seata.spring.boot.autoconfigure.SeataAutoConfiguration} 一并激活。</li>
 * </ul>
 * 不要在本类里做默认值兜底：{@code @ConditionalOnProperty} 的 {@code matchIfMissing} 语义只决定
 * 「本类装不装」，决定不了 Seata starter 自己的自动装配——那正是历史缺陷的来源
 * （starter 上 {@code matchIfMissing=true}，属性缺失即启用）。
 */
@Configuration
@ConditionalOnProperty(name = "seata.enabled", havingValue = "true")
public class SeataAutoConfig {
    // io.seata.spring.boot.autoconfigure.SeataAutoConfiguration 负责实际装配。
    // 本类仅作为条件开关：seata.enabled=true 时激活 Seata 自动装配。
}
