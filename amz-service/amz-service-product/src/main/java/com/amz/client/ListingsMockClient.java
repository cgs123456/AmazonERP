package com.amz.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Amazon SP-API Listings/Feeds 模拟客户端。
 * <p>
 * 由于 product 服务当前未接入 SP-API 凭证（LWA refresh token + 店铺凭证绑定），
 * 这里对 /feeds/2021-06-30/feeds 做模拟实现：
 * <ul>
 *   <li>{@link #submitFeed} 返回随机 UUID 作为 feedSubmissionId</li>
 *   <li>{@link #getFeedStatus} 返回 DONE + 随机 resultFeedDocumentId（字段名与官方 Feed
 *       模型 {@code definitions.Feed} 一致）</li>
 * </ul>
 * 仅在 {@code spring.profiles.active=mock} 时生效；生产部署必须禁用 mock（P0-01）。
 */
@Component
@Profile("mock")
public class ListingsMockClient implements ListingsClient {

    private static final Gson GSON = new Gson();

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
        result.addProperty("feedId", feedSubmissionId);
        result.addProperty("processingStatus", "DONE");
        result.addProperty("resultFeedDocumentId", UUID.randomUUID().toString());
        return result;
    }

    @Override
    public JsonObject getFeedResult(Long shopId, String feedSubmissionId) {
        log.info("getFeedResult (mock) shopId={} feedSubmissionId={}", shopId, feedSubmissionId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("feedId", feedSubmissionId);
        summary.put("resultFeedDocumentId", "mock-result-" + feedSubmissionId);
        summary.put("messagesProcessed", 1);
        summary.put("messagesAccepted", 1);
        summary.put("messagesInvalid", 0);
        summary.put("errors", 0);
        summary.put("warnings", 0);
        summary.put("successful", true);
        summary.put("partial", false);
        summary.put("failed", false);
        summary.put("issues", List.of());
        return GSON.toJsonTree(summary).getAsJsonObject();
    }
}
