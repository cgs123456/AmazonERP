package com.amz.client.fallback;

import com.amz.client.SpApiFinanceClient;
import com.amz.client.dto.RemoteFeeEstimate;
import com.amz.client.dto.RemoteFinancialEvent;
import com.amz.client.dto.RemoteReportInfo;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * spapi 财务接口降级工厂。
 * <p>
 * <b>刻意不做空数据降级</b>：财务域若静默返回空列表，
 * 上游会把「服务不可用」误判为「本期无结算」—— 利润、回款、索赔全部按 0 计算，
 * 且看起来像一个真实的业务结论。这里统一返回 failure，让调用方显式失败。
 */
@Slf4j
@Component
public class SpApiFinanceClientFallbackFactory implements FallbackFactory<SpApiFinanceClient> {

    @Override
    public SpApiFinanceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-spapi (finance) degraded: cause={}", cause.getMessage());
        String reason = "spapi finance service degraded: " + cause.getMessage();
        return new SpApiFinanceClient() {
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

            @Override
            public Result<List<RemoteFinancialEvent>> listEvents(Long shopId, String postedAfter,
                                                                String postedBefore) {
                return Result.failure(reason);
            }

            @Override
            public Result<RemoteFeeEstimate> estimateFees(Long shopId, String marketplaceId, String idType,
                                                          String idValue, String sku, BigDecimal price,
                                                          String currency) {
                return Result.failure(reason);
            }
        };
    }
}
