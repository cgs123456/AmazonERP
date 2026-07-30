package com.amz.config;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 动态数据源（读写分离）配置
 * <p>
 * 由 dynamic-datasource-spring-boot3-starter 自动装配。
 * 实际数据源 Bean 由 starter 依据 application.yml 中
 * {@code spring.datasource.dynamic.*} 自动创建为 {@link DynamicRoutingDataSource}，
 * 默认数据源为 master（写），从库为 slave（读）。
 * <p>
 * <b>使用方式</b>：在 Service/Mapper 方法或类上标注 {@code @DS("slave")} 即可路由到从库；
 * 不加注解默认走 master。
 * <pre>
 * &#64;Service
 * public class SpApiOrderSyncService {
 *     // 拉取订单后写入 master
 *     public void syncOrders(List&lt;Order&gt; orders) { ... }
 *
 *     &#64;DS("slave")           // 查询走从库
 *     public List&lt;Order&gt; listOrders(QueryDTO q) { ... }
 * }
 * </pre>
 * <p>
 * 当 {@code MYSQL_SLAVE_HOST} 未配置时，slave 配置回退到 master 地址，
 * 单机环境也能正常启动（不报错，只是读写都打到 master）。
 *
 * @see com.baomidou.dynamic.datasource.annotation.DS
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    private final DataSource dataSource;

    public DataSourceConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 启动时打印当前数据源拓扑，便于排查读写分离是否生效。
     */
    @PostConstruct
    public void logDataSourceTopology() {
        if (dataSource instanceof DynamicRoutingDataSource dynamicDs) {
            log.info("[DataSource] 动态数据源已启用，可用数据源: {}", dynamicDs.getDataSources().keySet());
        } else {
            log.warn("[DataSource] 当前 DataSource 不是 DynamicRoutingDataSource，读写分离未生效: {}",
                    dataSource.getClass().getName());
        }
    }
}
