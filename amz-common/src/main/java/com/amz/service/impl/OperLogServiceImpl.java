package com.amz.service.impl;

import com.amz.annotation.OperLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 操作审计落库写入器（amz-common 内部组件）。
 * <p>
 * 数据表：{@code amz_user.amz_oper_log}（amz-service-user 的 V3 迁移）。
 * 各业务服务的 {@code JdbcTemplate} 指向**自己的业务库**，因此这里用带库名限定的 SQL
 * 跨库写入——与 {@code FieldPermissionServiceImpl} 读
 * {@code amz_user.amz_field_permission} 是同一既有先例（同实例、单账号）。
 * <p>
 * <b>审计是旁路</b>，三条硬约束：
 * <ol>
 *   <li>任何写失败都只记日志并计数，<b>绝不抛异常、绝不阻断业务</b>；</li>
 *   <li>JdbcTemplate 缺失（amz-common 单独运行、message 服务无数据源）→ 直接跳过；</li>
 *   <li>表不存在（老环境未执行 V3）→ 只 WARN 一次，不刷屏。</li>
 * </ol>
 * 写入在异步线程执行，所有上下文（userId / IP / 参数）必须由
 * {@code OperLogAspect} 在**业务线程同步阶段**抓完再传入，本类不读
 * {@code UserContext}（异步线程里它是 null）。
 */
@Slf4j
@Service
public class OperLogServiceImpl implements com.amz.service.OperLogService {

    private static final String INSERT_SQL =
            "INSERT INTO amz_user.amz_oper_log"
                    + " (user_id, shop_id, module, action, description, method, params, result,"
                    + " ip, status, error_msg, trace_id, cost_time, create_time)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";

    /** JDBC 可选注入：宿主服务可能无数据源（如 amz-service-message）。 */
    @Autowired(required = false)
    private ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    /**
     * 表缺失标记：只 WARN 一次，避免每个操作都刷一条「表不存在」。
     * 审计是旁路，表没建出来不该把日志刷爆。
     */
    private volatile boolean tableMissingWarned = false;

    /** 写入失败计数（丢弃/异常合计），用于观测审计管道健康度。 */
    private final AtomicLong failureCount = new AtomicLong();

    private JdbcTemplate jdbc() {
        return jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
    }

    @Override
    public boolean record(OperLog operLog, Integer userId, Long shopId, String ip, String method,
                          String params, String result, String status, String errorMsg,
                          String traceId, long costMs) {
        JdbcTemplate template = jdbc();
        if (template == null) {
            return false;
        }
        try {
            template.update(INSERT_SQL,
                    userId == null ? null : userId.longValue(),
                    shopId,
                    operLog.module(),
                    operLog.action(),
                    operLog.description(),
                    truncate(method, 255),
                    params,
                    result,
                    ip,
                    status,
                    errorMsg,
                    traceId,
                    costMs);
            return true;
        } catch (Exception e) {
            long n = failureCount.incrementAndGet();
            String message = String.valueOf(e.getMessage());
            if (isTableMissing(message)) {
                if (!tableMissingWarned) {
                    tableMissingWarned = true;
                    log.warn("操作审计表 amz_user.amz_oper_log 不可用，审计只写文件不落库"
                            + "（需 amz-service-user 的 V3 迁移）。原因: {}", message);
                }
            } else if (n <= 3 || n % 100 == 0) {
                // 前 3 条必报，之后降频到每 100 条：避免 DB 抖动时审计日志刷屏
                log.warn("操作审计落库失败（第 {} 条，业务不受影响）。原因: {}", n, message);
            }
            return false;
        }
    }

    @Override
    public long failureCount() {
        return failureCount.get();
    }

    /**
     * 判断是否为「表不存在」类错误。
     * 用字符串判定而不是异常类型：MySQL 驱动对不同 SQLSTATE 抛的异常类型不一致，
     * 且这里只需要区分「环境没建表」与「其它写失败」两种处置（前者只提示一次）。
     */
    private boolean isTableMissing(String message) {
        String m = message == null ? "" : message.toLowerCase();
        return m.contains("doesn't exist")
                || m.contains("does not exist")
                || m.contains("unknown table")
                || m.contains("no such table");
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
