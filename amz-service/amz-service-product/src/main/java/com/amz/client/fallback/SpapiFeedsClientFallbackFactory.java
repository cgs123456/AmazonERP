package com.amz.client.fallback;

import com.amz.client.SpapiFeedsClient;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class SpapiFeedsClientFallbackFactory implements FallbackFactory<SpapiFeedsClient> {

    @Override
    public SpapiFeedsClient create(Throwable cause) {
        log.warn("Feign call to amz-service-spapi (feeds) degraded: cause={}", cause.getMessage());
        return new SpapiFeedsClient() {
            @Override
            public Result<String> submitFeed(SpapiFeedsClient.FeedSubmitRequest request) {
                return Result.failure("spapi feeds service degraded: " + cause.getMessage());
            }

            @Override
            public Result<Map<String, Object>> getFeedStatus(Long shopId, String feedId) {
                return Result.failure("spapi feeds service degraded: " + cause.getMessage());
            }
        };
    }
}
