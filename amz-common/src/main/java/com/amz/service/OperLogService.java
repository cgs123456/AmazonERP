package com.amz.service;

import com.amz.annotation.OperLog;

/**
 * 操作审计落库服务（amz-common 内部 API）。
 * <p>
 * 由 {@code OperLogAspect} 在异步线程调用，把审计记录写入
 * {@code amz_user.amz_oper_log}。实现见 {@code OperLogServiceImpl}。
 * <p>
 * <b>契约</b>：本接口的所有方法都<b>不得抛异常</b>——审计是旁路留痕，
 * 写失败只能记日志计数，不能反过来让业务操作失败。
 */
public interface OperLogService {

    /**
     * 写入一条审计记录。
     *
     * @param operLog  注解实例（提供 module / action / description）
     * @param userId   操作人用户 ID，可空（系统/定时任务）
     * @param shopId   操作所属店铺 ID，可空
     * @param ip       来源 IP，可空
     * @param method   方法签名
     * @param params   入参 JSON，可空
     * @param result   返回值 JSON，失败时为 null
     * @param status   SUCCESS / FAIL
     * @param errorMsg 异常信息，成功时为 null
     * @param traceId  链路追踪 ID，可空
     * @param costMs   耗时毫秒
     * @return 是否落库成功（false 表示降级为仅文件记录）
     */
    boolean record(OperLog operLog, Integer userId, Long shopId, String ip, String method,
                   String params, String result, String status, String errorMsg,
                   String traceId, long costMs);

    /**
     * 累计落库失败条数（含被拒绝策略丢弃的），供观测与测试断言。
     */
    long failureCount();
}
