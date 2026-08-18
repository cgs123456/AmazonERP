package com.amz.client;

import com.amz.result.Result;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Amazon SP-API Listings/Feeds 真实客户端。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * 通过 Feign 调用 amz-service-spapi 的 /spapi/feeds 接口，
 * 由 spapi 模块复用 LWA Token + AWS Sig V4 完成真实 SP-API Feeds 调用
 * （创建文档 → 上传 S3 → 提交 Feed → 查询状态）。
 * <p>
 * 与 ListingsMockClient 的区别：本实现真实发起 SP-API 请求，
 * 不再伪造 MOCK_FEED_ 前缀 feedId 或 DONE 占位状态。
 * 当 spapi 服务不可用或凭证缺失时抛出异常（由调用方 executeCopyTaskAsync 标记为 FAILED），
 * 而非静默降级为 mock，避免「假成功」误导业务流程。
 */
@Slf4j
@Component
@Profile("!mock")
public class ListingsRealClient implements ListingsClient {

    private final SpapiFeedsClient spapiFeedsClient;

    public ListingsRealClient(SpapiFeedsClient spapiFeedsClient) {
        this.spapiFeedsClient = spapiFeedsClient;
    }

    @Override
    public String submitFeed(Long shopId, String marketplaceId, String jsonlContent) {
        SpapiFeedsClient.FeedSubmitRequest req = new SpapiFeedsClient.FeedSubmitRequest();
        req.setShopId(shopId);
        req.setMarketplaceId(marketplaceId);
        req.setContent(jsonlContent);
        Result<String> result = spapiFeedsClient.submitFeed(req);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("SP-API Feed 提交失败 shopId=" + shopId
                    + " marketplaceId=" + marketplaceId + " : "
                    + (result == null ? "no response" : result.getMessage()));
        }
        log.info("ListingsRealClient.submitFeed success shopId={} marketplaceId={} feedId={}",
                shopId, marketplaceId, result.getData());
        return result.getData();
    }

    @Override
    public JsonObject getFeedStatus(Long shopId, String feedSubmissionId) {
        Result<Map<String, Object>> result = spapiFeedsClient.getFeedStatus(shopId, feedSubmissionId);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("SP-API Feed 状态查询失败 shopId=" + shopId
                    + " feedSubmissionId=" + feedSubmissionId + " : "
                    + (result == null ? "no response" : result.getMessage()));
        }
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, Object> e : result.getData().entrySet()) {
            putValue(obj, e.getKey(), e.getValue());
        }
        return obj;
    }

    @SuppressWarnings("unchecked")
    private void putValue(JsonObject obj, String key, Object value) {
        if (value == null) {
            obj.add(key, JsonNull.INSTANCE);
            return;
        }
        if (value instanceof String s) {
            obj.addProperty(key, s);
        } else if (value instanceof Boolean b) {
            obj.addProperty(key, b);
        } else if (value instanceof Number n) {
            if (value instanceof Integer || value instanceof Long) {
                obj.addProperty(key, n.longValue());
            } else {
                obj.addProperty(key, n.doubleValue());
            }
        } else if (value instanceof Map) {
            JsonObject child = new JsonObject();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                putValue(child, e.getKey(), e.getValue());
            }
            obj.add(key, child);
        } else {
            obj.addProperty(key, value.toString());
        }
    }
}
