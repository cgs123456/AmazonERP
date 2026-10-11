package com.amz.aspect;

import com.amz.annotation.OperLog;
import com.amz.context.UserContext;
import com.amz.service.OperLogService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 操作日志审计 AOP 切面。
 * <p>
 * 拦截所有标注 {@link OperLog} 的方法，采集操作人 userId、店铺、操作时间、模块、动作、
 * 方法参数、返回值、异常信息、请求 IP、链路 ID 与耗时，先写独立的 {@code oper-log}
 * 日志文件，再异步落库 {@code amz_user.amz_oper_log}（见 {@link OperLogService}）。
 * <p>
 * <b>两段式</b>：
 * <ul>
 *   <li><b>同步阶段（业务线程）</b>：只做 ThreadLocal / RequestContextHolder 读取。
 *       必须在这里抓完——{@code UserContext} 与 {@code MDC} 都不跨线程，
 *       异步线程里读到的 userId / traceId 一律是 null。</li>
 *   <li><b>异步阶段（{@code amzAuditExecutor}）</b>：序列化 + 文件 IO + DB 写入。</li>
 * </ul>
 * 审计是旁路：异步提交失败、DB 写失败都只降级为「仅文件记录」，绝不阻断业务。
 */
@Aspect
@Component
public class OperLogAspect {

    /**
     * 独立 logger，对应 logback 中 {@code oper-log} appender，仅输出到 oper-log.log。
     */
    private static final Logger operLogger = LoggerFactory.getLogger("oper-log");

    /**
     * params / result 序列化最大字符数，防止超大对象撑爆日志。
     */
    private static final int MAX_PAYLOAD_LENGTH = 2000;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * 自注入代理实例，确保 {@link #recordAsync} 通过 Spring 代理调用以触发 {@code @Async}。
     */
    @Lazy
    @Autowired
    private OperLogAspect self;

    /** 落库写入器可选注入：宿主无数据源或尚未执行 V3 迁移时切面仍只写文件。 */
    @Autowired(required = false)
    private OperLogService operLogService;

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint pjp, OperLog operLog) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Exception e) {
            error = e;
            throw e;
        } finally {
            long cost = System.currentTimeMillis() - start;
            // ↓↓↓ 同步阶段：必须在业务线程内完成所有 ThreadLocal / 请求上下文读取。
            // 这些值一旦进入异步线程就全部丢失（UserContext 与 MDC 都是线程绑定的）。
            Integer userId = UserContext.getUserId();
            Long shopId = resolveShopId();
            String ip = getClientIp();
            String traceId = resolveTraceId();
            String method = pjp.getSignature().toShortString();
            String params = toJson(pjp.getArgs());
            String resultJson = (error == null) ? toJson(result) : null;
            Throwable captured = error;
            OperLogService writer = this.operLogService;
            try {
                self.recordAsync(operLog, userId, shopId, ip, traceId, method, params,
                        resultJson, captured, cost, writer);
            } catch (Exception ex) {
                // 异步提交失败（队列满 / 池未就绪）时降级为同步记录，保证审计不丢失。
                // 注意：这里同步执行会走 DB 写，仅发生在异步池不可用这种极少数情况。
                recordAsync(operLog, userId, shopId, ip, traceId, method, params,
                        resultJson, captured, cost, writer);
            }
        }
    }

    /**
     * 异步记录操作日志。通过 self 代理调用以生效 {@code @Async}。
     * <p>
     * 显式指定 {@code amzAuditExecutor}：product 服务自带 {@code taskExecutor}
     * （core 2 / max 5），默认 {@code @Async} 会落到那个池，被 listing 复制这类
     * 长任务挤占；且审计专用池用 AbortPolicy（队列满丢弃）而不是 CallerRunsPolicy，
     * 不会把 DB 写回压到业务线程上。
     *
     * @param writer 在调用方解析好再传入：异步线程里 {@code @Autowired} 字段虽可读，
     *               但显式传参让本方法可被单测直接驱动（不依赖 Spring 上下文）
     */
    @Async("amzAuditExecutor")
    public void recordAsync(OperLog operLog, Integer userId, Long shopId, String ip, String traceId,
                            String method, String params, String result, Throwable error,
                            long cost, OperLogService writer) {
        boolean success = (error == null);
        String errorMsg = success ? null : safeMsg(error);
        String status = success ? "SUCCESS" : "FAIL";
        LocalDateTime operateTime = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault());

        // ① 文件留痕（始终执行，即使下面落库失败也不丢审计）
        operLogger.info(
                "OPER_LOG | userId={} | shopId={} | time={} | module={} | action={} | desc={}"
                        + " | method={} | ip={} | traceId={} | status={} | cost={}ms"
                        + " | params={} | result={} | error={}",
                userId,
                shopId,
                operateTime,
                operLog.module(),
                operLog.action(),
                operLog.description(),
                method,
                ip,
                traceId,
                status,
                cost,
                params,
                success ? result : "null",
                errorMsg
        );

        // ② 落库（旁路：写失败只计数，不影响业务）
        if (writer != null) {
            try {
                writer.record(operLog, userId, shopId, ip, method, params,
                        success ? result : null, status, errorMsg, traceId, cost);
            } catch (Exception e) {
                // 兜底：写入器契约上不抛异常，这里再包一层是防止实现变更把异常冒到异步线程
                LoggerFactory.getLogger(OperLogAspect.class)
                        .warn("操作审计落库异常，已降级为仅文件记录。原因: {}", e.getMessage());
            }
        }
    }

    /**
     * 解析审计归属店铺：取当前上下文授权列表中的第一个。
     * <p>
     * 取第一个而不是「全部记录」是因为 {@code amz_oper_log.shop_id} 是单列，
     * 而多数写操作的归属店铺就是请求头里那一个；跨店铺/系统级操作返回 null（列可空）。
     * 若未来要支持一次操作影响多店，应改成明细表而不是在这里塞逗号串。
     */
    private Long resolveShopId() {
        try {
            List<Long> shops = UserContext.getShops();
            if (shops != null && !shops.isEmpty()) {
                return shops.get(0);
            }
        } catch (Exception ignored) {
            // 上下文不可用（定时任务等）
        }
        return null;
    }

    /**
     * 取链路追踪 ID，优先 MDC（SkyWalking agent 注入 traceId）。
     * 必须在同步阶段读：MDC 是线程绑定的，异步线程里为空。
     */
    private String resolveTraceId() {
        try {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                return traceId;
            }
            return MDC.get("trace_id");
        } catch (Exception ignored) {
            // MDC 不可用
        }
        return null;
    }

    /**
     * 从当前请求上下文获取客户端 IP，优先取反向代理转发头。
     * 非 Web 上下文（如定时任务）时返回 null。
     */
    private String getClientIp() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                String ip = req.getHeader("X-Forwarded-For");
                if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
                    ip = req.getHeader("X-Real-IP");
                }
                if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
                    ip = req.getRemoteAddr();
                }
                // X-Forwarded-For 可能含多级代理，取首个
                if (ip != null && ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        } catch (Exception ignored) {
            // 非请求线程或上下文不可用
        }
        return null;
    }

    /**
     * 将对象序列化为 JSON 并截断，避免超大入参/返回值撑爆日志。
     */
    private String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            String json = GSON.toJson(obj);
            return truncate(json);
        } catch (Exception e) {
            return "<serialize-failed: " + safeMsg(e) + ">";
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= MAX_PAYLOAD_LENGTH ? s : s.substring(0, MAX_PAYLOAD_LENGTH) + "...(truncated)";
    }

    private String safeMsg(Throwable t) {
        return t == null ? null : t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
