package com.amz.client;

import com.google.gson.JsonObject;

/**
 * Amazon SP-API Listings/Feeds 客户端接口。
 * <p>
 * 生产环境对接路径：
 * <ul>
 *   <li>POST /feeds/2021-06-30/feeds 提交 Feed</li>
 *   <li>GET /feeds/2021-06-30/feeds/{feedId} 查询处理状态</li>
 * </ul>
 * 鉴权方式：LWA Token + AWS Sig V4。
 * <p>
 * 通过 Spring Profile 切换实现：
 * <ul>
 *   <li>{@code mock}：{@link ListingsMockClient} 离线模拟（随机 UUID + DONE）</li>
 *   <li>{@code !mock}：{@link ListingsRealClient} 真实 API 对接骨架</li>
 * </ul>
 */
public interface ListingsClient {

    /**
     * 提交 Feed（JSONL）到 SP-API /feeds/2021-06-30/feeds。
     *
     * @param shopId        店铺 ID
     * @param marketplaceId 目标 Marketplace ID
     * @param jsonlContent  JSONL 格式的 Listing 数据
     * @return feedSubmissionId
     */
    String submitFeed(Long shopId, String marketplaceId, String jsonlContent);

    /**
     * 查询 Feed 处理状态。
     *
     * @param shopId           店铺 ID
     * @param feedSubmissionId Feed 提交 ID
     * @return 包含 processingStatus 等字段的 JSON 对象
     */
    JsonObject getFeedStatus(Long shopId, String feedSubmissionId);
}
