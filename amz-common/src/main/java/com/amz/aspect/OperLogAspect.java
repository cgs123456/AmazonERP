package com.amz.aspect;

import com.amz.annotation.OperLog;
import com.amz.context.UserContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * 操作日志审计 AOP 切面。
 * <p>
 * 拦截所有标注 {@link OperLog} 的方法，采集操作人 userId、操作时间、模块、动作、
 * 方法参数、返回值、异常信息、请求 IP 与耗时，并通过 {@code @Async} 异步写入
 * 独立的 {@code oper-log} 日志文件，不影响业务线程性能。
 * <p>
 * 同步阶段仅做 ThreadLocal 上下文与请求头的读取（必须在业务线程内完成），
 * 序列化与文件 IO 全部下沉到异步方法。
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
            // 必须在业务线程内同步采集 ThreadLocal / RequestContextHolder 上下文
            Integer userId = UserContext.getUserId();
            String ip = getClientIp();
            String method = pjp.getSignature().toShortString();
            String params = toJson(pjp.getArgs());
            String resultJson = (error == null) ? toJson(result) : null;
            Throwable captured = error;
            try {
                self.recordAsync(operLog, userId, ip, method, params, resultJson, captured, cost);
            } catch (Exception ex) {
                // 异步提交失败时降级为同步记录，保证审计不丢失
                recordAsync(operLog, userId, ip, method, params, resultJson, captured, cost);
            }
        }
    }

    /**
     * 异步记录操作日志。通过 self 代理调用以生效 {@code @Async}。
     */
    @Async
    public void recordAsync(OperLog operLog, Integer userId, String ip, String method,
                            String params, String result, Throwable error, long cost) {
        boolean success = (error == null);
        String errorMsg = success ? null : safeMsg(error);
        LocalDateTime operateTime = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault());

        // 结构化字段，便于后续 ELK / Loki 解析
        operLogger.info(
                "OPER_LOG | userId={} | time={} | module={} | action={} | desc={} | method={} | ip={} | status={} | cost={}ms | params={} | result={} | error={}",
                userId,
                operateTime,
                operLog.module(),
                operLog.action(),
                operLog.description(),
                method,
                ip,
                success ? "SUCCESS" : "FAIL",
                cost,
                params,
                success ? result : "null",
                errorMsg
        );
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
