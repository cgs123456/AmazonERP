package com.amz.client;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Amazon SP-API Listings/Feeds 模拟客户端。
 * <p>
 * 由于 product 服务当前未接入 SP-API 凭证（LWA / AWS Sig V4），
 * 这里对 /feeds/2021-06-30/feeds 做模拟实现：
 * <ul>
 *   <li>{@link #submitFeed} 返回随机 UUID 作为 feedSubmissionId</li>
 *   <li>{@link #getFeedStatus} 返回 DONE / SUCCESS</li>
 * </ul>
 * 仅在 {@code spring.profiles.active=mock} 时生效。
 */
@Component
@Profile("mock")
public class ListingsMockClient implements ListingsClient {

    private static final Logger log = LoggerFactory.getLogger(ListingsMockClient.class);

    @Override
    public String submitFeed(Long shopId, String marketplaceId, String jsonlContent) {
        String feedSubmissionId = UUID.randomUUID().toString();
        log.info("submitFeed (mock) shopId={} marketplaceId={} feedSubmissionId={} contentLen={}",
                shopId, marketplaceId, feedSubmissionId,
                jsonlContent == null ? 0 : jsonlContent.length());
        return feedSubmissionId;
    }

    @Override
    public JsonObject getFeedStatus(Long shopId, String feedSubmissionId) {
        log.info("getFeedStatus (mock) shopId={} feedSubmissionId={}", shopId, feedSubmissionId);
        JsonObject result = new JsonObject();
        result.addProperty("feedSubmissionId", feedSubmissionId);
        result.addProperty("processingStatus", "DONE");
        result.addProperty("resultDocumentId", UUID.randomUUID().toString());
        return result;
    }
}
