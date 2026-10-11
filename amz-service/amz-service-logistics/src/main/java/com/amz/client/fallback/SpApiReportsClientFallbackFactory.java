package com.amz.client.fallback;

import com.amz.client.SpApiReportsClient;
import com.amz.client.dto.RemoteReportInfo;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 报表客户端降级工厂。
 * <p>
 * <b>刻意返回失败而非空报表</b>：spapi 不可用时若返回空字符串/空状态，
 * 退货同步会把「没拉到」记成「本期无退货」，与「真的没有退货」不可区分——
 * 与 {@code ProcurementCostClientFallbackFactory} 同一条纪律。
 * 同步是可重入的（幂等键拦重复），失败只要不推进游标即可下次重试。
 */
@Slf4j
@Component
public class SpApiReportsClientFallbackFactory implements FallbackFactory<SpApiReportsClient> {

    @Override
    public SpApiReportsClient create(Throwable cause) {
        log.warn("Feign call to amz-service-spapi (reports) degraded", cause);
        final String reason = "spapi service degraded: " + cause.getMessage();
        return new SpApiReportsClient() {
            @Override
            public Result<String> requestReport(Long shopId, String marketplaceId, String reportType,
                                                String dataStartTime, String dataEndTime) {
                return Result.failure(reason);
            }

            @Override
            public Result<RemoteReportInfo> getReport(String reportId, Long shopId) {
                return Result.failure(reason);
            }

            @Override
            public Result<String> downloadDocument(String documentId, Long shopId) {
                return Result.failure(reason);
            }
        };
    }
}
