package com.amz.client;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Amazon SP-API Listings/Feeds 客户端（简化版）。
 * <p>
 * 由于 product 服务当前未接入 SP-API 凭证（LWA / AWS Sig V4），
 * 这里对 /feeds/2021-06-30/feeds 做模拟实现：
 * <ul>
 *   <li>{@link #submitFeed} 返回随机 UUID 作为 feedSubmissionId</li>
 *   <li>{@link #getFeedStatus} 返回 DONE / SUCCESS</li>
 * </ul>
 * 待 SP-API 凭证就绪后替换为真实调用即可。
 */
@Component
public class ListingsClient {

    private static final Logger log = LoggerFactory.getLogger(ListingsClient.class);

    /**
     * 提交 Feed（JSONL）到 SP-API /feeds/2021-06-30/feeds。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId 目标 Marketplace ID
     * @param jsonlContent  JSONL 格式的 Listing 数据
     * @return feedSubmissionId（当前为模拟 UUID）
     */
    public String submitFeed(Long shopId, String marketplaceId, String jsonlContent) {
        String feedSubmissionId = UUID.randomUUID().toString();
        log.info("submitFeed (mock) shopId={} marketplaceId={} feedSubmissionId={} contentLen={}",
                shopId, marketplaceId, feedSubmissionId,
                jsonlContent == null ? 0 : jsonlContent.length());
        return feedSubmissionId;
    }

    /**
     * 查询 Feed 处理状态。
     *
     * @param shopId            店铺 ID
     * @param feedSubmissionId  Feed 提交 ID
     * @return 包含 processingStatus 等字段的 JSON 对象（当前模拟返回 DONE）
     */
    public JsonObject getFeedStatus(Long shopId, String feedSubmissionId) {
        log.info("getFeedStatus (mock) shopId={} feedSubmissionId={}", shopId, feedSubmissionId);
        JsonObject result = new JsonObject();
        result.addProperty("feedSubmissionId", feedSubmissionId);
        result.addProperty("processingStatus", "DONE");
        result.addProperty("resultDocumentId", UUID.randomUUID().toString());
        return result;
    }
}
