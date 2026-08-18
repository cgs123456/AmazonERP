package com.amz.client.fallback;

import com.amz.client.ReportServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ReportServiceClientFallbackFactory implements FallbackFactory<ReportServiceClient> {

    @Override
    public ReportServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-report degraded: cause={}", cause.getMessage());
        return new ReportServiceClient() {
            @Override
            public Result<List<Map<String, Object>>> getSalesTrend(Long shopId, Integer days) {
                return Result.failure("report service degraded: " + cause.getMessage());
            }
        };
    }
}
