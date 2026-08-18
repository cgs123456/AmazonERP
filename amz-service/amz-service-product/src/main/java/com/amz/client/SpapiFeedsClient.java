package com.amz.client;

import com.amz.client.fallback.SpapiFeedsClientFallbackFactory;
import com.amz.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

/**
 * amz-service-spapi Feeds 接口 Feign 客户端（product 服务侧）。
 * <p>
 * 调用 spapi 模块的 /spapi/feeds/submit 与 /spapi/feeds/status/{shopId}/{feedId}，
 * 由 spapi 模块执行真实 SP-API Feeds 调用（创建文档 → 上传 S3 → 提交 Feed → 查询状态）。
 * 服务不可用时由 {@link SpapiFeedsClientFallbackFactory} 返回失败而非伪造结果，避免「假成功」。
 */
@FeignClient(name = "amz-service-spapi", contextId = "spapiFeedsClient",
        fallbackFactory = SpapiFeedsClientFallbackFactory.class)
@RequestMapping("/spapi/feeds")
public interface SpapiFeedsClient {

    /**
     * 提交 Feed，返回 SP-API feedId。
     */
    @PostMapping("/submit")
    Result<String> submitFeed(@RequestBody FeedSubmitRequest request);

    /**
     * 查询 Feed 处理状态，返回 SP-API 原始状态字段（processingStatus 等）。
     */
    @GetMapping("/status/{shopId}/{feedId}")
    Result<Map<String, Object>> getFeedStatus(@PathVariable("shopId") Long shopId,
                                              @PathVariable("feedId") String feedId);

    /**
     * Feed 提交请求体：shopId + 目标 marketplaceId + JSON 内容。
     */
    class FeedSubmitRequest {
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
