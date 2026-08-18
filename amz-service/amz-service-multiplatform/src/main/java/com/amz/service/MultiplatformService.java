package com.amz.service;

import com.amz.model.*;

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

    List<PlatformAccount> listAccounts(Long shopId);

    boolean deleteAccount(Long id);

    boolean testConnection(Long accountId);

    // ===== 商品同步与映射 =====

    int syncProducts(Long shopId, String platform);

    List<PlatformProduct> listProducts(Long shopId, String platform);

    boolean mapProduct(Long platformProductId, String amazonAsin, String amazonSku);

    // ===== 消息管理 =====

    int syncMessages(Long shopId, String platform);

    List<PlatformMessage> listMessages(Long shopId, String platform, Integer status);

    boolean replyMessage(Long messageId, String replyContent);

    boolean assignMessage(Long messageId, String assignedTo);

    // ===== 库存聚合 =====

    int syncInventory(Long shopId, String platform);

    List<PlatformInventory> listPlatformInventory(Long shopId, String platform);

    Map<String, Object> aggregatedInventory(Long shopId);

    // ===== Webhook =====

    WebhookEvent receiveWebhook(String platform, String eventType, String eventId, String payload, Long shopId);

    List<WebhookEvent> listWebhookEvents(Long shopId, String status);

    // ===== OAuth 开放 API =====

    OauthApp registerApp(OauthApp app);

    List<OauthApp> listApps(Long ownerShopId);

    OauthToken generateToken(String appKey, String appSecret, String[] scopes, Long shopId);
}
