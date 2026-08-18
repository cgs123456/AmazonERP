package com.amz.client.fallback;

import com.amz.client.LogisticsServiceClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LogisticsServiceClientFallbackFactory implements FallbackFactory<LogisticsServiceClient> {

    @Override
    public LogisticsServiceClient create(Throwable cause) {
        log.warn("Feign call to amz-service-logistics degraded: cause={}", cause.getMessage());
        return new LogisticsServiceClient() {
            private String failMsg() { return "logistics service degraded: " + cause.getMessage(); }

            @Override
            public Result<List<Map<String, Object>>> getTracking(Long shipmentId) {
                return Result.failure(failMsg());
            }

            @Override
            public Result<List<Map<String, Object>>> listShipments(Long shopId, String status) {
                return Result.failure(failMsg());
            }

            @Override
            public Result<Map<String, Object>> getGlobalInventoryView(Long shopId) {
                return Result.failure(failMsg());
            }

            @Override
            public Result<Map<String, Object>> getAgingAnalysis(Long shopId) {
                return Result.failure(failMsg());
            }

            @Override
            public Result<Map<String, Object>> compareShippingQuotes(Long shopId, String originPort,
                    String destinationPort, Double weightKg, Double volumeCbm) {
                return Result.failure(failMsg());
            }
        };
    }
}
