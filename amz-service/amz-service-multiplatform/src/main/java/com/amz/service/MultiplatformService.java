package com.amz.service;

import com.amz.model.OauthApp;
import com.amz.model.OauthToken;
import com.amz.model.PlatformAccount;
import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;
import com.amz.model.WebhookEvent;

import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import java.util.List;
import java.util.Map;

/**
 * 多平台管理服务（Phase 3 升级版）。
 * <p>
 * 新增：
 * <ul>
 *   <li>平台账号管理（CRUD + 连接测试）</li>
 *   <li>全平台商品同步与映射</li>
 *   <li>多平台消息统一管理</li>
 *   <li>多平台库存聚合视图</li>
 *   <li>Webhook 事件接收与处理</li>
 *   <li>OAuth 应用注册与 Token 发放</li>
 * </ul>
 */
public interface MultiplatformService {

    // ===== 平台账号管理 =====

    PlatformAccount createAccount(PlatformAccount account);

    PlatformAccount updateAccount(Long id, PlatformAccount account);

    PageResult<PlatformAccount> listAccounts(Long shopId, PageRequest page);

    boolean deleteAccount(Long id);

    boolean testConnection(Long accountId);

    // ===== 商品同步与映射 =====

    int syncProducts(Long shopId, String platform);

    PageResult<PlatformProduct> listProducts(Long shopId, String platform, PageRequest page);

    boolean mapProduct(Long platformProductId, String amazonAsin, String amazonSku);

    // ===== 消息管理 =====

    int syncMessages(Long shopId, String platform);

    PageResult<PlatformMessage> listMessages(Long shopId, String platform, String status, PageRequest page);

    boolean replyMessage(Long messageId, String replyContent);

    boolean assignMessage(Long messageId, String assignedTo);

    // ===== 库存聚合 =====

    int syncInventory(Long shopId, String platform);

    PageResult<PlatformInventory> listPlatformInventory(Long shopId, String platform, PageRequest page);

    Map<String, Object> aggregatedInventory(Long shopId);

    // ===== Webhook =====

    WebhookEvent receiveWebhook(String platform, String eventType, String eventId, String payload, Long shopId);

    PageResult<WebhookEvent> listWebhookEvents(Long shopId, String status, PageRequest page);

    // ===== OAuth 开放 API =====

    /**
     * 注册 OAuth 应用。明文密钥仅本次随结果返回（字段 {@code appSecret}），
     * 后续只存 SHA-256，请调用方妥善保存，丢失后走 rotateAppSecret 轮换。
     */
    Map<String, Object> registerApp(OauthApp app);

    /**
     * 轮换应用密钥（需归属店铺权限），返回新的明文密钥（仅此一次）。
     */
    Map<String, Object> rotateAppSecret(Long appId);

    PageResult<OauthApp> listApps(Long ownerShopId, PageRequest page);

    OauthToken generateToken(String appKey, String appSecret, String[] scopes, Long shopId);
}
