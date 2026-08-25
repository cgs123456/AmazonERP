package com.amz.controller;

import com.amz.client.FeedsClient;
import com.amz.context.UserContext;
import com.amz.result.Result;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SP-API Feeds 对外接口。
 * <p>
 * 由 {@link FeedsClient} 执行真实 SP-API Feeds 调用（创建文档 → 上传 S3 → 提交 Feed → 查询状态）。
 * 网关已配置 {@code Path=/spapi/**} 路由到本服务，裸 {@code /feeds} 前缀外部不可达，故映射在 {@code /spapi/feeds} 下。
 */
@RestController
@RequestMapping("/spapi/feeds")
public class FeedsController {

    private static final Logger log = LoggerFactory.getLogger(FeedsController.class);

    @Autowired
    private FeedsClient feedsClient;

    /**
     * 提交 Listing Feed 到 SP-API，返回 feedId。
     */
    @PostMapping("/submit")
    public Result<String> submit(@RequestBody FeedSubmitRequest request) {
        if (request == null || request.getShopId() == null
                || request.getMarketplaceId() == null || request.getContent() == null) {
            return Result.failure("shopId / marketplaceId / content 均不能为空");
        }
        if (!UserContext.isShopAllowed(request.getShopId())) {
            return Result.failure("无权限操作该店铺 shopId=" + request.getShopId());
        }
        try {
            String feedId = feedsClient.submitFeed(
                    request.getShopId(), request.getMarketplaceId(), request.getContent());
            return Result.success(feedId);
        } catch (Exception e) {
            log.error("FeedsController.submit failed shopId={}", request.getShopId(), e);
            return Result.failure("feed submit failed");
        }
    }

    /**
     * 查询 Feed 处理状态（SP-API processingStatus 等字段）。
     * 以 Map 返回，避免跨服务序列化 Gson JsonObject 时 Jackson 无默认构造器的问题。
     */
    @GetMapping("/status/{shopId}/{feedId}")
    public Result<Map<String, Object>> status(@PathVariable Long shopId,
                                              @PathVariable String feedId) {
        if (shopId == null || feedId == null) {
            return Result.failure("shopId / feedId 均不能为空");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            return Result.failure("无权限操作该店铺 shopId=" + shopId);
        }
        try {
            JsonObject json = feedsClient.getFeedStatus(shopId, feedId);
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                map.put(entry.getKey(), gsonToJava(entry.getValue()));
            }
            return Result.success(map);
        } catch (Exception e) {
            log.error("FeedsController.status failed shopId={} feedId={}", shopId, feedId, e);
            return Result.failure("feed status failed");
        }
    }

    /**
     * 将 Gson JsonElement 递归转换为 Jackson 友好的普通 Java 对象。
     */
    private Object gsonToJava(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString()) {
                return p.getAsString();
            }
            if (p.isNumber()) {
                return p.getAsNumber();
            }
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            return p.getAsString();
        }
        if (el.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                m.put(e.getKey(), gsonToJava(e.getValue()));
            }
            return m;
        }
        if (el.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : el.getAsJsonArray()) {
                list.add(gsonToJava(item));
            }
            return list;
        }
        return null;
    }

    /**
     * Feed 提交请求体。
     */
    public static class FeedSubmitRequest {
        private Long shopId;
        private String marketplaceId;
        private String content;

        public Long getShopId() {
            return shopId;
        }

        public void setShopId(Long shopId) {
            this.shopId = shopId;
        }

        public String getMarketplaceId() {
            return marketplaceId;
        }

        public void setMarketplaceId(String marketplaceId) {
            this.marketplaceId = marketplaceId;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
