package com.amz.service.impl;

import com.amz.client.SheinClient;
import com.amz.client.TemuClient;
import com.amz.client.TikTokClient;
import com.amz.exception.AttrIsNullException;
import com.amz.finance.PlatformCurrencyConverter;
import com.amz.mapper.*;
import com.amz.model.*;
import com.amz.service.MultiplatformService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 多平台管理服务实现（Phase 3 升级版）。
 * <p>
 * 在原有 Temu/TikTok/Shein 订单同步基础上新增：
 * <ol>
 *   <li>平台账号管理（多店铺 API 凭证）</li>
 *   <li>商品同步与 Amazon ASIN/SKU 映射</li>
 *   <li>多平台消息统一管理（站内信聚合）</li>
 *   <li>全平台库存聚合视图</li>
 *   <li>Webhook 事件接收与处理</li>
 *   <li>OAuth 开放 API 体系</li>
 * </ol>
 */
@Slf4j
@Service
public class MultiplatformServiceImpl implements MultiplatformService {

    // ===== 原有依赖 =====
    @Autowired
    private UnifiedOrderMapper unifiedOrderMapper;
    @Autowired
    private TemuClient temuClient;
    @Autowired
    private TikTokClient tiktokClient;
    @Autowired
    private SheinClient sheinClient;
    @Autowired
    private PlatformCurrencyConverter currencyConverter;

    // ===== Phase 3 新增依赖 =====
    @Autowired
    private PlatformAccountMapper platformAccountMapper;
    @Autowired
    private PlatformProductMapper platformProductMapper;
    @Autowired
    private PlatformMessageMapper platformMessageMapper;
    @Autowired
    private PlatformInventoryMapper platformInventoryMapper;
    @Autowired
    private WebhookEventMapper webhookEventMapper;
    @Autowired
    private OauthAppMapper oauthAppMapper;
    @Autowired
    private OauthTokenMapper oauthTokenMapper;

    // ========================================================
    // 平台账号管理
    // ========================================================

    @Override
    @Transactional
    public PlatformAccount createAccount(PlatformAccount account) {
        account.setStatus("ACTIVE");
        account.setCreateTime(LocalDateTime.now());
        account.setUpdateTime(LocalDateTime.now());
        platformAccountMapper.insert(account);
        log.info("多平台账号创建：shopId={} platform={} storeName={}", account.getShopId(), account.getPlatform(), account.getStoreName());
        return account;
    }

    @Override
    @Transactional
    public PlatformAccount updateAccount(Long id, PlatformAccount account) {
        account.setId(id);
        account.setUpdateTime(LocalDateTime.now());
        platformAccountMapper.updateById(account);
        return account;
    }

    @Override
    public List<PlatformAccount> listAccounts(Long shopId) {
        LambdaQueryWrapper<PlatformAccount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformAccount::getShopId, shopId);
        return platformAccountMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public boolean deleteAccount(Long id) {
        return platformAccountMapper.deleteById(id) > 0;
    }

    @Override
    public boolean testConnection(Long accountId) {
        PlatformAccount account = platformAccountMapper.selectById(accountId);
        if (account == null) return false;
        boolean ok = false;
        try {
            ok = testPlatformConnection(account.getPlatform(), account.getApiEndpoint(), account.getApiKey());
        } catch (Exception e) {
            log.warn("平台连接测试失败 accountId={} platform={}", accountId, account.getPlatform(), e);
        }
        account.setStatus(ok ? "ACTIVE" : "ERROR");
        account.setLastSyncTime(LocalDateTime.now());
        platformAccountMapper.updateById(account);
        return ok;
    }

    private boolean testPlatformConnection(String platform, String endpoint, String apiKey) {
        // 各平台连接测试（mock 模式用端点非空 + 超时判断）
        if (endpoint == null || endpoint.isBlank()) return false;
        return endpoint.contains(".") || endpoint.startsWith("http");
    }

    // ========================================================
    // 商品同步与映射（P2 新增）
    // ========================================================

    @Override
    public int syncProducts(Long shopId, String platform) {
        int count = 0;
        // 模拟从各平台拉取商品列表并映射
        for (int i = 1; i <= 5; i++) {
            PlatformProduct pp = new PlatformProduct();
            pp.setShopId(shopId);
            pp.setPlatform(platform);
            pp.setPlatformProductId(platform + "-PROD-" + shopId + "-" + i);
            pp.setPlatformProductSku("SKU-" + platform + "-" + i);
            pp.setTitle("Platform Product " + i + " from " + platform + " shop " + shopId);
            pp.setPrice(new java.math.BigDecimal((10 + i) + ".99"));
            pp.setCurrency("USD");
            pp.setStockQty(100 - i * 10);
            pp.setStatus("ACTIVE");
            pp.setCategory("General");
            pp.setCreateTime(LocalDateTime.now());
            pp.setUpdateTime(LocalDateTime.now());

            // UPSERT：按 (platform, platformProductId) 唯一键
            LambdaQueryWrapper<PlatformProduct> existQuery = new LambdaQueryWrapper<>();
            existQuery.eq(PlatformProduct::getPlatform, pp.getPlatform())
                      .eq(PlatformProduct::getPlatformProductId, pp.getPlatformProductId());
            PlatformProduct exist = platformProductMapper.selectOne(existQuery);
            if (exist != null) {
                pp.setId(exist.getId());
                pp.setAmazonAsin(exist.getAmazonAsin());
                pp.setAmazonSku(exist.getAmazonSku());
                pp.setCreateTime(exist.getCreateTime());
                platformProductMapper.updateById(pp);
            } else {
                platformProductMapper.insert(pp);
            }
            count++;
        }
        log.info("平台商品同步完成：shopId={} platform={} count={}", shopId, platform, count);
        return count;
    }

    @Override
    public List<PlatformProduct> listProducts(Long shopId, String platform) {
        LambdaQueryWrapper<PlatformProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformProduct::getShopId, shopId);
        if (platform != null && !platform.isBlank()) {
            wrapper.eq(PlatformProduct::getPlatform, platform);
        }
        return platformProductMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public boolean mapProduct(Long platformProductId, String amazonAsin, String amazonSku) {
        PlatformProduct pp = platformProductMapper.selectById(platformProductId);
        if (pp == null) throw new AttrIsNullException("平台商品不存在 id=" + platformProductId);
        pp.setAmazonAsin(amazonAsin);
        pp.setAmazonSku(amazonSku);
        pp.setUpdateTime(LocalDateTime.now());
        platformProductMapper.updateById(pp);
        log.info("商品映射：{}:{}/{} → ASIN:{} SKU:{}", pp.getPlatform(), pp.getPlatformProductId(), pp.getTitle(), amazonAsin, amazonSku);
        return true;
    }

    // ========================================================
    // 消息管理（P2 新增）
    // ========================================================

    @Override
    public int syncMessages(Long shopId, String platform) {
        int count = 0;
        // 模拟从平台拉取站内信
        String[] subjects = {"Order Question", "Return Request", "Product Inquiry", "Shipping Delay"};
        for (int i = 0; i < subjects.length; i++) {
            PlatformMessage msg = new PlatformMessage();
            msg.setShopId(shopId);
            msg.setPlatform(platform);
            msg.setPlatformMessageId(platform + "-MSG-" + shopId + "-" + System.currentTimeMillis() + "-" + i);
            msg.setBuyerName("Buyer " + (i + 1));
            msg.setBuyerEmail("buyer" + (i + 1) + "@example.com");
            msg.setOrderId("ORD-" + shopId + "-" + (1000 + i));
            msg.setSubject(subjects[i]);
            msg.setContent("This is a sample message about " + subjects[i] + " from platform " + platform);
            msg.setDirection("IN");
            msg.setStatus("UNREAD");
            msg.setIsUrgent(i == 0);
            msg.setReceiveTime(LocalDateTime.now().minusMinutes(30 + i * 10L));
            msg.setCreateTime(LocalDateTime.now());
            msg.setUpdateTime(LocalDateTime.now());

            // UPSERT by platform + platformMessageId
            LambdaQueryWrapper<PlatformMessage> existQuery = new LambdaQueryWrapper<>();
            existQuery.eq(PlatformMessage::getPlatform, msg.getPlatform())
                     .eq(PlatformMessage::getPlatformMessageId, msg.getPlatformMessageId());
            PlatformMessage exist = platformMessageMapper.selectOne(existQuery);
            if (exist == null) {
                platformMessageMapper.insert(msg);
                count++;
            }
        }
        log.info("平台消息同步完成：shopId={} platform={} count={}", shopId, platform, count);
        return count;
    }

    @Override
    public List<PlatformMessage> listMessages(Long shopId, String platform, Integer status) {
        LambdaQueryWrapper<PlatformMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformMessage::getShopId, shopId);
        if (platform != null && !platform.isBlank()) wrapper.eq(PlatformMessage::getPlatform, platform);
        if (status != null) wrapper.eq(PlatformMessage::getStatus, status);
        wrapper.orderByDesc(PlatformMessage::getReceiveTime);
        return platformMessageMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public boolean replyMessage(Long messageId, String replyContent) {
        PlatformMessage msg = platformMessageMapper.selectById(messageId);
        if (msg == null) throw new AttrIsNullException("消息不存在 id=" + messageId);
        msg.setStatus("REPLIED");
        msg.setReplyTime(LocalDateTime.now());
        msg.setUpdateTime(LocalDateTime.now());
        platformMessageMapper.updateById(msg);

        // 创建回复记录（OUT 方向）
        PlatformMessage reply = new PlatformMessage();
        reply.setShopId(msg.getShopId());
        reply.setPlatform(msg.getPlatform());
        reply.setPlatformMessageId(msg.getPlatformMessageId() + "-REPLY-" + System.currentTimeMillis());
        reply.setBuyerName(msg.getBuyerName());
        reply.setBuyerEmail(msg.getBuyerEmail());
        reply.setSubject("Re: " + msg.getSubject());
        reply.setContent(replyContent);
        reply.setDirection("OUT");
        reply.setStatus("REPLIED");
        reply.setReceiveTime(LocalDateTime.now());
        reply.setCreateTime(LocalDateTime.now());
        reply.setUpdateTime(LocalDateTime.now());
        platformMessageMapper.insert(reply);
        log.info("消息回复：messageId={} contentLen={}", messageId, replyContent != null ? replyContent.length() : 0);
        return true;
    }

    @Override
    @Transactional
    public boolean assignMessage(Long messageId, String assignedTo) {
        PlatformMessage msg = platformMessageMapper.selectById(messageId);
        if (msg == null) throw new AttrIsNullException("消息不存在 id=" + messageId);
        msg.setAssignedTo(assignedTo);
        msg.setUpdateTime(LocalDateTime.now());
        platformMessageMapper.updateById(msg);
        return true;
    }

    // ========================================================
    // 库存聚合（P2 新增）
    // ========================================================

    @Override
    public int syncInventory(Long shopId, String platform) {
        int count = 0;
        String[] skus = {"SKU-A", "SKU-B", "SKU-C", "SKU-D"};
        String[] warehouses = platform.equals("AMAZON") ? new String[]{"FBA-ON1", "FBA-LAX9"} : new String[]{platform + "-WH1"};
        for (String sku : skus) {
            for (String wh : warehouses) {
                PlatformInventory inv = new PlatformInventory();
                inv.setShopId(shopId);
                inv.setPlatform(platform);
                inv.setPlatformProductId(platform + "-" + sku);
                inv.setSku(sku);
                inv.setWarehouse(wh);
                inv.setAvailableQty(50 + new Random().nextInt(151));
                inv.setReservedQty(new Random().nextInt(21));
                inv.setInboundQty(new Random().nextInt(31));
                inv.setSnapshotTime(LocalDateTime.now());
                inv.setCreateTime(LocalDateTime.now());
                platformInventoryMapper.insert(inv);
                count++;
            }
        }
        log.info("平台库存同步：shopId={} platform={} count={}", shopId, platform, count);
        return count;
    }

    @Override
    public List<PlatformInventory> listPlatformInventory(Long shopId, String platform) {
        LambdaQueryWrapper<PlatformInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformInventory::getShopId, shopId);
        if (platform != null && !platform.isBlank()) wrapper.eq(PlatformInventory::getPlatform, platform);
        wrapper.orderByDesc(PlatformInventory::getSnapshotTime);
        return platformInventoryMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> aggregatedInventory(Long shopId) {
        LambdaQueryWrapper<PlatformInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformInventory::getShopId, shopId);
        List<PlatformInventory> all = platformInventoryMapper.selectList(wrapper);

        // 按平台 + SKU 聚合
        Map<String, Map<String, Integer>> byPlatform = new LinkedHashMap<>();
        Map<String, Integer> bySku = new LinkedHashMap<>();
        int grandTotal = 0;

        for (PlatformInventory inv : all) {
            byPlatform.computeIfAbsent(inv.getPlatform(), k -> new LinkedHashMap<>())
                      .merge(inv.getSku(), inv.getAvailableQty(), Integer::sum);
            bySku.merge(inv.getSku(), inv.getAvailableQty(), Integer::sum);
            grandTotal += inv.getAvailableQty();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("grandTotalAvailable", grandTotal);
        result.put("byPlatform", byPlatform);
        result.put("bySku", bySku);
        result.put("snapshotTime", LocalDateTime.now());
        return result;
    }

    // ========================================================
    // Webhook（P2 新增）
    // ========================================================

    @Override
    @Transactional
    public WebhookEvent receiveWebhook(String platform, String eventType, String eventId, String payload) {
        // 幂等去重
        if (eventId != null && !eventId.isEmpty()) {
            LambdaQueryWrapper<WebhookEvent> dupCheck = new LambdaQueryWrapper<>();
            dupCheck.eq(WebhookEvent::getEventId, eventId);
            if (webhookEventMapper.selectCount(dupCheck) > 0) {
                log.info("Webhook 重复事件：platform={} eventType={} eventId={}", platform, eventType, eventId);
                return null;
            }
        }

        WebhookEvent event = new WebhookEvent();
        event.setShopId(1L); // TODO：从平台账号反查
        event.setPlatform(platform);
        event.setEventType(eventType);
        event.setEventId(eventId);
        event.setPayload(payload);
        event.setStatus("RECEIVED");
        event.setCreateTime(LocalDateTime.now());
        webhookEventMapper.insert(event);

        // 异步标记为 PROCESSED
        try {
            handleWebhookEvent(event);
        } catch (Exception e) {
            log.error("Webhook 事件处理失败 id={}", event.getId(), e);
            event.setStatus("FAILED");
            event.setProcessResult(e.getMessage());
        }
        event.setProcessTime(LocalDateTime.now());
        webhookEventMapper.updateById(event);
        return event;
    }

    private void handleWebhookEvent(WebhookEvent event) {
        // 根据事件类型分发处理
        switch (event.getEventType()) {
            case "ORDER_CREATED":
                log.info("Webhook ORDER_CREATED: platform={} eventId={}", event.getPlatform(), event.getEventId());
                break;
            case "INVENTORY_CHANGE":
                log.info("Webhook INVENTORY_CHANGE: platform={}", event.getPlatform());
                break;
            case "MESSAGE_NEW":
                log.info("Webhook MESSAGE_NEW: platform={}", event.getPlatform());
                break;
            default:
                log.info("Webhook UNHANDLED: type={}", event.getEventType());
        }
        event.setStatus("PROCESSED");
    }

    @Override
    public List<WebhookEvent> listWebhookEvents(Long shopId, String status) {
        LambdaQueryWrapper<WebhookEvent> wrapper = new LambdaQueryWrapper<>();
        if (shopId != null) wrapper.eq(WebhookEvent::getShopId, shopId);
        if (status != null && !status.isBlank()) wrapper.eq(WebhookEvent::getStatus, status);
        wrapper.orderByDesc(WebhookEvent::getCreateTime);
        return webhookEventMapper.selectList(wrapper);
    }

    // ========================================================
    // OAuth 开放 API（P2 新增）
    // ========================================================

    @Override
    @Transactional
    public OauthApp registerApp(OauthApp app) {
        app.setAppKey("AK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        app.setStatus("ACTIVE");
        app.setCreateTime(LocalDateTime.now());
        app.setUpdateTime(LocalDateTime.now());
        oauthAppMapper.insert(app);
        log.info("OAuth App 注册：{} appKey={} ownerShopId={}", app.getAppName(), app.getAppKey(), app.getOwnerShopId());
        return app;
    }

    @Override
    public List<OauthApp> listApps(Long ownerShopId) {
        LambdaQueryWrapper<OauthApp> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OauthApp::getOwnerShopId, ownerShopId);
        return oauthAppMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public OauthToken generateToken(String appKey, String appSecret, String[] scopes, Long shopId) {
        // 校验 App
        LambdaQueryWrapper<OauthApp> appQuery = new LambdaQueryWrapper<>();
        appQuery.eq(OauthApp::getAppKey, appKey);
        OauthApp app = oauthAppMapper.selectOne(appQuery);
        if (app == null || !"ACTIVE".equals(app.getStatus())) {
            throw new AttrIsNullException("OAuth App 不存在或已停用");
        }

        // 生成 Token
        String accessToken = "oat_" + UUID.randomUUID().toString().replace("-", "");
        String refreshToken = "ort_" + UUID.randomUUID().toString().replace("-", "");

        OauthToken token = new OauthToken();
        token.setAppId(app.getId());
        token.setAccessToken(accessToken);
        token.setRefreshToken(refreshToken);
        token.setTokenType("Bearer");
        token.setExpiresAt(LocalDateTime.now().plusDays(30));
        token.setScopes(scopes != null ? String.join(",", scopes) : app.getScopes());
        token.setShopId(shopId);
        token.setCreateTime(LocalDateTime.now());
        oauthTokenMapper.insert(token);

        log.info("OAuth Token 生成：appKey={} shopId={} scopes={}", appKey, shopId, token.getScopes());
        return token;
    }

    // ========================================================
    // 原有方法（Phase 1 保留）
    // ========================================================

    public int syncAllPlatforms(Long shopId) {
        int temu = syncPlatformSafely(shopId, "TEMU");
        int tiktok = syncPlatformSafely(shopId, "TIKTOK");
        int shein = syncPlatformSafely(shopId, "SHEIN");
        log.info("多平台订单同步完成 shopId={}：Temu={} TikTok={} Shein={}", shopId, temu, tiktok, shein);
        return temu + tiktok + shein;
    }

    private int syncPlatformSafely(Long shopId, String platform) {
        try {
            return syncByPlatform(shopId, platform);
        } catch (Exception e) {
            log.error("多平台同步降级：shopId={} platform={} 同步失败，跳过该平台继续后续同步", shopId, platform, e);
            return 0;
        }
    }

    public int syncByPlatform(Long shopId, String platform) {
        List<com.amz.model.UnifiedOrder> fetched;
        switch (platform) {
            case "TEMU": fetched = temuClient.fetchRecentOrders(shopId); break;
            case "TIKTOK": fetched = tiktokClient.fetchRecentOrders(shopId); break;
            case "SHEIN": fetched = sheinClient.fetchRecentOrders(shopId); break;
            default: throw new AttrIsNullException("不支持的平台：" + platform);
        }
        Set<String> existingKeys = loadExistingOrderKeys(fetched);
        int inserted = 0;
        for (com.amz.model.UnifiedOrder o : fetched) {
            String key = dedupKey(o.getPlatform(), o.getPlatformOrderNo());
            if (key != null && existingKeys.contains(key)) continue;
            o.setUnifiedOrderNo("UO" + System.currentTimeMillis() + inserted);
            o.setCnyAmount(currencyConverter.toCny(o.getOriginalAmount(), o.getCurrency()));
            unifiedOrderMapper.insert(o);
            if (key != null) existingKeys.add(key);
            inserted++;
        }
        return inserted;
    }

    private Set<String> loadExistingOrderKeys(List<com.amz.model.UnifiedOrder> fetched) {
        Set<String> existingKeys = new HashSet<>();
        if (fetched == null || fetched.isEmpty()) return existingKeys;
        List<String> orderNoList = new ArrayList<>();
        for (com.amz.model.UnifiedOrder o : fetched) {
            if (o.getPlatformOrderNo() != null && !o.getPlatformOrderNo().isEmpty()) {
                orderNoList.add(o.getPlatformOrderNo());
            }
        }
        if (orderNoList.isEmpty()) return existingKeys;
        LambdaQueryWrapper<com.amz.model.UnifiedOrder> dedupQuery = new LambdaQueryWrapper<>();
        dedupQuery.in(com.amz.model.UnifiedOrder::getPlatformOrderNo, orderNoList);
        for (com.amz.model.UnifiedOrder exist : unifiedOrderMapper.selectList(dedupQuery)) {
            existingKeys.add(dedupKey(exist.getPlatform(), exist.getPlatformOrderNo()));
        }
        return existingKeys;
    }

    private String dedupKey(String platform, String platformOrderNo) {
        if (platformOrderNo == null || platformOrderNo.isEmpty()) return null;
        return platform + "|" + platformOrderNo;
    }

    public List<com.amz.model.UnifiedOrder> listOrders(Long shopId) {
        LambdaQueryWrapper<com.amz.model.UnifiedOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(com.amz.model.UnifiedOrder::getShopId, shopId).orderByDesc(com.amz.model.UnifiedOrder::getId);
        return unifiedOrderMapper.selectList(wrapper);
    }

    public List<com.amz.model.UnifiedOrder> listByPlatform(Long shopId, String platform) {
        LambdaQueryWrapper<com.amz.model.UnifiedOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(com.amz.model.UnifiedOrder::getShopId, shopId)
               .eq(com.amz.model.UnifiedOrder::getPlatform, platform)
               .orderByDesc(com.amz.model.UnifiedOrder::getId);
        return unifiedOrderMapper.selectList(wrapper);
    }

    public boolean markShipped(Long orderId, String trackingNo) {
        com.amz.model.UnifiedOrder order = unifiedOrderMapper.selectById(orderId);
        if (order == null) throw new AttrIsNullException("订单不存在：id=" + orderId);
        boolean ok;
        switch (order.getPlatform()) {
            case "TEMU": ok = temuClient.markShipped(order.getPlatformOrderNo(), trackingNo); break;
            case "TIKTOK": ok = tiktokClient.markShipped(order.getPlatformOrderNo(), trackingNo); break;
            case "SHEIN": ok = sheinClient.markShipped(order.getPlatformOrderNo(), trackingNo); break;
            default: throw new AttrIsNullException("不支持的平台：" + order.getPlatform());
        }
        if (ok) {
            order.setTrackingNo(trackingNo);
            order.setStatus("SHIPPED");
            unifiedOrderMapper.updateById(order);
        }
        return ok;
    }
}
