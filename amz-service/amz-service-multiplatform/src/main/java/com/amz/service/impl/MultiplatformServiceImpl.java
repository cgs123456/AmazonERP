package com.amz.service.impl;

import com.amz.client.SheinClient;
import com.amz.client.PlatformDataClient;
import com.amz.client.TemuClient;
import com.amz.client.TikTokClient;
import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.finance.PlatformCurrencyConverter;
import com.amz.mapper.OauthAppMapper;
import com.amz.mapper.OauthTokenMapper;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.mapper.PlatformInventoryMapper;
import com.amz.mapper.PlatformMessageMapper;
import com.amz.mapper.PlatformProductMapper;
import com.amz.mapper.UnifiedOrderMapper;
import com.amz.multiplatform.TimeCursorPageSupport;
import com.amz.model.OauthApp;
import com.amz.model.OauthToken;
import com.amz.model.PlatformAccount;
import com.amz.model.PlatformInventory;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;
import com.amz.model.UnifiedOrder;
import com.amz.model.UnifiedOrderItems;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.MultiplatformService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
 *   <li>OAuth 应用管理（注册/轮换/列表）——<b>只有这三件可用</b></li>
 * </ol>
 *
 * <p>关于第 5 项要说清楚，避免误读（§S33.94）：这里<b>没有</b>「OAuth 开放 API 体系」，
 * 只有应用自身的管理接口。{@link #generateToken} 会签发 token，但签出来的串
 * 不被任何代码校验——系统目前没有受 token 保护的资源。要把它变成真正的开放 API，
 * 需要补受保护资源 + 校验过滤器 + 过期/吊销/刷新，属排期项。
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
    private OauthAppMapper oauthAppMapper;
    @Autowired
    private OauthTokenMapper oauthTokenMapper;

    // ========================================================
    // 平台账号管理
    // ========================================================

    @Override
    @Transactional
    public PlatformAccount createAccount(PlatformAccount account) {
        // 请求体里的 shopId 切面覆盖不到，归属只能在这里按行判定
        requireShopOnRow(account.getShopId(), "多平台账号");
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
        PlatformAccount existed = platformAccountMapper.selectById(id);
        if (existed == null) {
            throw new AttrIsNullException("平台账号不存在 id=" + id);
        }
        requireShopOnRow(existed.getShopId(), "多平台账号");
        // 锁定归属店铺：禁止借更新把行搬到其他店铺
        account.setId(id);
        account.setShopId(existed.getShopId());
        account.setUpdateTime(LocalDateTime.now());
        platformAccountMapper.updateById(account);
        return account;
    }

    @Override
    public PageResult<PlatformAccount> listAccounts(Long shopId, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        Long cursorId = req.cursorId();
        LambdaQueryWrapper<PlatformAccount> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformAccount::getShopId, shopId);
        if (cursorId != null) {
            wrapper.lt(PlatformAccount::getId, cursorId);
        }
        wrapper.orderByDesc(PlatformAccount::getId).last("LIMIT " + req.probeSize());
        List<PlatformAccount> rows = platformAccountMapper.selectList(wrapper);
        rows.forEach(MultiplatformServiceImpl::redactAccountSecrets);
        return PageResult.of(rows, req.size(), a -> PageRequest.encodeCursor(a.getId()));
    }

    /**
     * 凭证列只写不回显：apiKey 是明文，其余三列是密文；
     * 读取接口把它们带出去只会扩大泄露面（与 SP-API 凭证端点同一约定）。
     */
    private static void redactAccountSecrets(PlatformAccount account) {
        if (account == null) {
            return;
        }
        account.setApiKey(null);
        account.setApiSecretEncrypted(null);
        account.setAccessTokenEncrypted(null);
        account.setRefreshTokenEncrypted(null);
    }

    @Override
    @Transactional
    public boolean deleteAccount(Long id) {
        PlatformAccount existed = platformAccountMapper.selectById(id);
        if (existed == null) {
            return false;
        }
        requireShopOnRow(existed.getShopId(), "多平台账号");
        return platformAccountMapper.deleteById(id) > 0;
    }

    @Override
    public boolean testConnection(Long accountId) {
        PlatformAccount account = platformAccountMapper.selectById(accountId);
        if (account == null) return false;
        requireShopOnRow(account.getShopId(), "平台账号");
        // 客户端分派放在探测之外：不支持的平台（亚马逊）在这里点名拒绝，
        // 而不是把账号写成 ERROR——「这条探测我们不提供」和「探测到了故障」是两回事。
        PlatformDataClient client = dataClient(account.getPlatform());
        // 真探测：向平台发一次已鉴权的只读请求（复用各家已实现的订单读），
        // 而不是校验端点字符串格式——后者从来没碰过网络，却曾被用来改写账号状态。
        boolean ok = client.probeConnection(account.getShopId());
        account.setStatus(ok ? "ACTIVE" : "ERROR");
        // 探测不是同步：lastSyncTime 只能由真正的同步任务推进，否则运维看到的"最近同步"是假的。
        platformAccountMapper.updateById(account);
        log.info("平台连接探测完成 accountId={} platform={} ok={}", accountId, account.getPlatform(), ok);
        return ok;
    }

    // ========================================================
    // 商品同步与映射（P2 新增）
    // ========================================================

    @Override
    public int syncProducts(Long shopId, String platform) {
        // 数据来源必须是平台客户端；造数曾直接写在本方法里（与 profile 无关），
        // 会污染 platform_product 上的平台→ASIN/SKU 映射。
        PlatformDataClient client = dataClient(platform);
        List<PlatformProduct> fetched = client.fetchProducts(shopId);
        int count = 0;
        for (PlatformProduct pp : fetched) {
            pp.setShopId(shopId);
            pp.setPlatform(platform);
            // shop_id 必须进 exist 查询：同一平台下不同店铺会拿到同一个
            // platform_product_id（同一供货商的货分销给多家店）。旧查询只按
            // (platform, platform_product_id) 匹配，命中别的店的行后
            // setId(exist.getId()) + updateById，把那行整条改写成自己店铺的
            // ——原店铺的商品从自己列表里消失，它人工建的 ASIN/SKU 映射也一并易主。
            // 这不是越权（入口有 @ShopScoped + @RequireRole），是租户边界漏在了查询条件上。
            LambdaQueryWrapper<PlatformProduct> existQuery = new LambdaQueryWrapper<>();
            existQuery.eq(PlatformProduct::getShopId, shopId)
                      .eq(PlatformProduct::getPlatform, pp.getPlatform())
                      .eq(PlatformProduct::getPlatformProductId, pp.getPlatformProductId());
            PlatformProduct exist = platformProductMapper.selectOne(existQuery);
            if (exist != null) {
                pp.setId(exist.getId());
                // 人工建立的映射与首次同步时间不能被同步覆盖
                pp.setAmazonAsin(exist.getAmazonAsin());
                pp.setAmazonSku(exist.getAmazonSku());
                pp.setCreateTime(exist.getCreateTime());
                pp.setUpdateTime(LocalDateTime.now());
                platformProductMapper.updateById(pp);
            } else {
                pp.setCreateTime(LocalDateTime.now());
                pp.setUpdateTime(LocalDateTime.now());
                platformProductMapper.insert(pp);
            }
            count++;
        }
        log.info("平台商品同步完成：shopId={} platform={} count={}", shopId, platform, count);
        return count;
    }


    @Override
    public PageResult<PlatformProduct> listProducts(Long shopId, String platform, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        Long cursorId = req.cursorId();
        LambdaQueryWrapper<PlatformProduct> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformProduct::getShopId, shopId);
        if (platform != null && !platform.isBlank()) {
            wrapper.eq(PlatformProduct::getPlatform, platform);
        }
        if (cursorId != null) {
            wrapper.lt(PlatformProduct::getId, cursorId);
        }
        wrapper.orderByDesc(PlatformProduct::getId).last("LIMIT " + req.probeSize());
        List<PlatformProduct> rows = platformProductMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(), x -> PageRequest.encodeCursor(x.getId()));
    }

    @Override
    @Transactional
    public boolean mapProduct(Long platformProductId, String amazonAsin, String amazonSku) {
        PlatformProduct pp = platformProductMapper.selectById(platformProductId);
        if (pp == null) throw new AttrIsNullException("平台商品不存在 id=" + platformProductId);
        // 这里以前没有任何归属判定：任何登录用户都能把别人店铺的商品改映射到自己的 ASIN/SKU 上。
        requireShopOnRow(pp.getShopId(), "平台商品");
        if (amazonAsin == null || amazonAsin.isBlank()) {
            throw new AttrIsNullException("ASIN 不能为空");
        }
        pp.setAmazonAsin(amazonAsin.trim().toUpperCase(java.util.Locale.ROOT));
        pp.setAmazonSku(amazonSku == null || amazonSku.isBlank() ? null : amazonSku.trim());
        pp.setUpdateTime(LocalDateTime.now());
        platformProductMapper.updateById(pp);
        log.info("商品映射：{}:{}/{} → ASIN:{} SKU:{}", pp.getPlatform(), pp.getPlatformProductId(), pp.getTitle(), pp.getAmazonAsin(), pp.getAmazonSku());
        return true;
    }

    // ========================================================
    // 消息管理（P2 新增）
    // ========================================================

    @Override
    public int syncMessages(Long shopId, String platform) {
        PlatformDataClient client = dataClient(platform);
        List<PlatformMessage> fetched = client.fetchMessages(shopId);
        int count = 0;
        for (PlatformMessage msg : fetched) {
            msg.setShopId(shopId);
            msg.setPlatform(platform);
            // shop_id 必须进 exist 查询，理由同 syncProducts：不同店铺会在同一平台
            // 收到相同的 platform_message_id（平台用供货商侧的消息主键）。
            // 旧查询命中别的店的行后什么都不做——本店这条消息永远不落库，
            // 且不 insert 不 update、无日志无异常，客服看到的是「消息不存在」
            // 而不是「同步失败」。两者都不会报错，只是失败方式不同。
            LambdaQueryWrapper<PlatformMessage> existQuery = new LambdaQueryWrapper<>();
            existQuery.eq(PlatformMessage::getShopId, shopId)
                      .eq(PlatformMessage::getPlatform, msg.getPlatform())
                      .eq(PlatformMessage::getPlatformMessageId, msg.getPlatformMessageId());
            if (platformMessageMapper.selectOne(existQuery) == null) {
                msg.setCreateTime(LocalDateTime.now());
                msg.setUpdateTime(LocalDateTime.now());
                platformMessageMapper.insert(msg);
                count++;
            }
        }
        log.info("平台消息同步完成：shopId={} platform={} count={}", shopId, platform, count);
        return count;
    }


    @Override
    public PageResult<PlatformMessage> listMessages(Long shopId, String platform, String status, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        TimeCursorPageSupport.TimeCursor cursor = TimeCursorPageSupport.parse(req, "receive_time");
        LambdaQueryWrapper<PlatformMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformMessage::getShopId, shopId);
        if (platform != null && !platform.isBlank()) wrapper.eq(PlatformMessage::getPlatform, platform);
        if (status != null && !status.isBlank()) wrapper.eq(PlatformMessage::getStatus, status);
        if (cursor != null) {
            TimeCursorPageSupport.before(wrapper, PlatformMessage::getReceiveTime, PlatformMessage::getId, cursor);
        }
        wrapper.orderByDesc(PlatformMessage::getReceiveTime)
               .orderByDesc(PlatformMessage::getId)
               .last("LIMIT " + req.probeSize());
        List<PlatformMessage> rows = platformMessageMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(),
                m -> PageRequest.encodeCursor(TimeCursorPageSupport.payload(m.getReceiveTime(), m.getId())));
    }

    @Override
    @Transactional
    public boolean replyMessage(Long messageId, String replyContent) {
        // 与 markShipped 的运单号同款必填校验：空内容「发送成功」会让客服页显示已回复、
        // 买家收到一条空白消息；mock 档下更是直接记出一条无意义的 REPLIED 行。
        if (replyContent == null || replyContent.isBlank()) {
            throw new AttrIsNullException("回复内容不能为空");
        }
        PlatformMessage msg = platformMessageMapper.selectById(messageId);
        if (msg == null) throw new AttrIsNullException("消息不存在 id=" + messageId);
        requireShopOnRow(msg.getShopId(), "平台消息");

        PlatformMessage outbound = new PlatformMessage();
        outbound.setShopId(msg.getShopId());
        outbound.setPlatform(msg.getPlatform());
        outbound.setBuyerName(msg.getBuyerName());
        outbound.setBuyerEmail(msg.getBuyerEmail());
        outbound.setSubject("Re: " + msg.getSubject());
        outbound.setContent(replyContent);
        outbound.setDirection("OUT");

        // 先真发、后记账：平台收下（返回它自己的消息 ID）才允许本地出现 REPLIED。
        // 旧顺序是先标 REPLIED 再插一条 LOCAL-REPLY- 备注，而全程没人向平台发过请求——
        // 客服页面就此显示「已回复」，买家什么都没收到。
        // 未接入时 sendMessage 抛 UnsupportedOperationException，一行都不写。
        // 残余风险与 markShipped 同类：平台已收、本地写入却回滚，重试会重复发送；
        // 真接入时需要平台侧的幂等键，这里不假装已经解决。
        String platformMessageId = dataClient(msg.getPlatform()).sendMessage(outbound);

        LocalDateTime now = LocalDateTime.now();
        msg.setStatus("REPLIED");
        msg.setReplyTime(now);
        msg.setUpdateTime(now);
        platformMessageMapper.updateById(msg);

        outbound.setPlatformMessageId(platformMessageId);
        outbound.setStatus("REPLIED");
        outbound.setReceiveTime(now);
        outbound.setCreateTime(now);
        outbound.setUpdateTime(now);
        platformMessageMapper.insert(outbound);
        log.info("平台站内信已发送并记账：messageId={} platform={} platformMessageId={} contentLen={}",
                messageId, msg.getPlatform(), platformMessageId,
                replyContent != null ? replyContent.length() : 0);
        return true;
    }

    @Override
    @Transactional
    public boolean assignMessage(Long messageId, String assignedTo) {
        PlatformMessage msg = platformMessageMapper.selectById(messageId);
        if (msg == null) throw new AttrIsNullException("消息不存在 id=" + messageId);
        requireShopOnRow(msg.getShopId(), "平台消息");
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
        // 顺序很关键：先拉到数据再替换快照。旧实现先 delete 再生成随机假库存，
        // 拉取失败时既丢了上一轮快照又写入假数。
        List<PlatformInventory> fetched = dataClient(platform).fetchInventory(shopId);
        if (fetched == null || fetched.isEmpty()) {
            log.warn("平台库存同步未取到数据，保留上一轮快照：shopId={} platform={}", shopId, platform);
            return 0;
        }
        LambdaQueryWrapper<PlatformInventory> cleanWrapper = new LambdaQueryWrapper<>();
        cleanWrapper.eq(PlatformInventory::getShopId, shopId);
        cleanWrapper.eq(PlatformInventory::getPlatform, platform);
        platformInventoryMapper.delete(cleanWrapper);
        int count = 0;
        for (PlatformInventory inv : fetched) {
            inv.setShopId(shopId);
            inv.setPlatform(platform);
            inv.setSnapshotTime(LocalDateTime.now());
            inv.setCreateTime(LocalDateTime.now());
            platformInventoryMapper.insert(inv);
            count++;
        }
        log.info("平台库存同步：shopId={} platform={} count={}", shopId, platform, count);
        return count;
    }


    @Override
    public PageResult<PlatformInventory> listPlatformInventory(Long shopId, String platform, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        TimeCursorPageSupport.TimeCursor cursor = TimeCursorPageSupport.parse(req, "snapshot_time");
        LambdaQueryWrapper<PlatformInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformInventory::getShopId, shopId);
        if (platform != null && !platform.isBlank()) wrapper.eq(PlatformInventory::getPlatform, platform);
        if (cursor != null) {
            TimeCursorPageSupport.before(wrapper, PlatformInventory::getSnapshotTime, PlatformInventory::getId, cursor);
        }
        wrapper.orderByDesc(PlatformInventory::getSnapshotTime)
               .orderByDesc(PlatformInventory::getId)
               .last("LIMIT " + req.probeSize());
        List<PlatformInventory> rows = platformInventoryMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(),
                i -> PageRequest.encodeCursor(TimeCursorPageSupport.payload(i.getSnapshotTime(), i.getId())));
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
        // 这是本次聚合的计算时刻，不是平台侧库存快照时间；沿用 snapshotTime 会被读成后者
        result.put("computedAt", LocalDateTime.now());
        return result;
    }
    // ========================================================
    // OAuth 应用管理（§S33.94：`generateToken` 是未完成半成品，见其方法注释）
    // ========================================================

    @Override
    @Transactional
    public Map<String, Object> registerApp(OauthApp app) {
        // owner_shop_id 在 DDL 里是 NOT NULL：把「没有归属」当成「不用校验」只会绕过判定然后撞库。
        if (app.getOwnerShopId() == null) {
            throw new AttrIsNullException("应用必须归属一个店铺");
        }
        requireShopOnRow(app.getOwnerShopId(), "OAuth 应用");
        app.setAppKey("AK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        // 明文密钥仅本次返回，后续只存 SHA-256（无 crypto 依赖，用 JDK 内置实现）
        String plainSecret = "ASK_" + UUID.randomUUID().toString().replace("-", "");
        app.setAppSecretEncrypted(sha256Hex(plainSecret));
        app.setStatus("ACTIVE");
        app.setCreateTime(LocalDateTime.now());
        app.setUpdateTime(LocalDateTime.now());
        oauthAppMapper.insert(app);
        log.info("OAuth App 注册：{} appKey={} ownerShopId={}", app.getAppName(), app.getAppKey(), app.getOwnerShopId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appId", app.getId());
        result.put("appKey", app.getAppKey());
        result.put("appSecret", plainSecret);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> rotateAppSecret(Long appId) {
        OauthApp app = oauthAppMapper.selectById(appId);
        if (app == null) {
            throw new AttrIsNullException("OAuth App 不存在：id=" + appId);
        }
        requireShopOnRow(app.getOwnerShopId(), "OAuth 应用");
        String plainSecret = "ASK_" + UUID.randomUUID().toString().replace("-", "");
        app.setAppSecretEncrypted(sha256Hex(plainSecret));
        app.setUpdateTime(LocalDateTime.now());
        oauthAppMapper.updateById(app);
        log.info("OAuth App 密钥轮换：appKey={}", app.getAppKey());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appId", app.getId());
        result.put("appKey", app.getAppKey());
        result.put("appSecret", plainSecret);
        return result;
    }

    @Override
    public PageResult<OauthApp> listApps(Long ownerShopId, PageRequest page) {
        LambdaQueryWrapper<OauthApp> wrapper = new LambdaQueryWrapper<>();
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        Long cursorId = req.cursorId();
        wrapper.eq(OauthApp::getOwnerShopId, ownerShopId);
        if (cursorId != null) {
            wrapper.lt(OauthApp::getId, cursorId);
        }
        wrapper.orderByDesc(OauthApp::getId).last("LIMIT " + req.probeSize());
        List<OauthApp> rows = oauthAppMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(), a -> PageRequest.encodeCursor(a.getId()));
    }

    /**
     * 按平台标识取数据客户端。亚马逊不在本模块拉取（库存/商品由 spapi 侧负责），
     * 因此不再像旧实现那样为 AMAZON 分支生成两个假 FBA 仓库。
     */
    private PlatformDataClient dataClient(String platform) {
        if (platform == null) {
            throw new AttrIsNullException("平台标识不能为空");
        }
        switch (platform) {
            case "TEMU": return temuClient;
            case "TIKTOK": return tiktokClient;
            case "SHEIN": return sheinClient;
            default: throw new AttrIsNullException("不支持的平台：" + platform
                    + "（亚马逊商品与库存请走 spapi 侧同步）");
        }
    }

    /**
     * 签发 OAuth token。<b>半成品——签发即终点。</b>
     *
     * <p>下列校验都是真的：appKey/appSecret/shopId 必填、App 存在且 ACTIVE、
     * 密钥 SHA-256 比对、token 绑定应用归属店铺（防止持单应用凭证越权签发他店 token）、
     * scope 取交集且交集为空拒绝。
     *
     * <p>缺的是后半程：返回的 access/refresh token 写入 `amz_oauth_token` 之后，
     * 全仓没有任何代码读这张表。没有校验端点、没有鉴权过滤器、没有过期判定、
     * 没有吊销、没有刷新。所以它<b>不授予任何访问权</b>。
     *
     * <p>记录在此而不是加 TODO（§S33.94）：外部已确认无使用方，
     * 补全需要「受保护资源 + 校验过滤器 + 过期/吊销/刷新」一整套，属排期项。
     * 在那之前，任何读到本方法的人都应知道：这里的校验防的是「签发流程本身出错」，
     * 不是「未授权访问系统」。
     */
    @Override
    @Transactional
    public OauthToken generateToken(String appKey, String appSecret, String[] scopes, Long shopId) {
        // 请求体绑定后必填位不再由 Spring 兜底，缺参必须在入口处显式拒绝（fail-closed）
        if (appKey == null || appKey.isBlank()) {
            throw new AttrIsNullException("appKey 不能为空");
        }
        if (shopId == null) {
            throw new AttrIsNullException("shopId 不能为空");
        }
        // 校验 App
        LambdaQueryWrapper<OauthApp> appQuery = new LambdaQueryWrapper<>();
        appQuery.eq(OauthApp::getAppKey, appKey);
        OauthApp app = oauthAppMapper.selectOne(appQuery);
        if (app == null || !"ACTIVE".equals(app.getStatus())) {
            throw new AttrIsNullException("OAuth App 不存在或已停用");
        }

        // 校验应用密钥：仅凭 appKey 不得签发（历史未初始化密钥的行拒绝并指引轮换）
        if (appSecret == null || app.getAppSecretEncrypted() == null
                || !sha256Hex(appSecret).equals(app.getAppSecretEncrypted())) {
            log.warn("OAuth Token 签发拒绝：密钥不匹配 appKey={}", appKey);
            throw new AttrIsNullException("应用密钥不正确或未初始化，请先轮换密钥");
        }
        // token 绑定应用归属店铺，防止持单应用凭证越权签发他店 token
        // （归属为空的历史数据放行，由密钥校验兜底）
        if (app.getOwnerShopId() != null && !app.getOwnerShopId().equals(shopId)) {
            throw new IllegalStateException("shopId 必须为应用归属店铺");
        }
        // scopes 取交集：未传则继承应用全部范围；交集为空直接拒绝
        Set<String> allowed = splitScopes(app.getScopes());
        final Set<String> granted;
        if (scopes == null || scopes.length == 0) {
            granted = allowed;
        } else {
            granted = new LinkedHashSet<>(Arrays.asList(scopes));
            granted.retainAll(allowed);
            if (granted.isEmpty()) {
                throw new AttrIsNullException("无有效授权范围");
            }
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
        token.setScopes(String.join(",", granted));
        token.setShopId(shopId);
        token.setCreateTime(LocalDateTime.now());
        oauthTokenMapper.insert(token);

        log.info("OAuth Token 生成：appKey={} shopId={} scopes={}", appKey, shopId, token.getScopes());
        return token;
    }

    private static Set<String> splitScopes(String scopes) {
        Set<String> set = new LinkedHashSet<>();
        if (scopes == null) {
            return set;
        }
        for (String s : scopes.split(",")) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return set;
    }


    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ========================================================
    // 原有方法（Phase 1 保留）
    // ========================================================

    /**
     * 全平台订单同步的可见结果。
     * <p>
     * 原来这里返回一个 int：{@code syncPlatformSafely} 把每个平台的异常吞成 0，
     * 于是「三个平台全挂」与「确实没有新单」是同一个数字，页面上只能显示「同步到 0 条」。
     * 现在把尝试/成功/失败分开报，失败的是哪几个平台也点名，异常仍只记日志不外抛
     * （单平台故障不该让另外两个平台的同步作废）。
     */
    public record OrderSyncSummary(int attempted, int succeeded, int failed,
                                   int inserted, List<String> failedPlatforms) {
    }

    public OrderSyncSummary syncAllPlatforms(Long shopId) {
        int inserted = 0;
        int succeeded = 0;
        List<String> failedPlatforms = new ArrayList<>();
        for (String platform : List.of("TEMU", "TIKTOK", "SHEIN")) {
            try {
                int n = syncByPlatform(shopId, platform);
                inserted += n;
                succeeded++;
            } catch (Exception e) {
                failedPlatforms.add(platform);
                log.error("多平台同步降级：shopId={} platform={} 同步失败，跳过该平台继续后续同步",
                        shopId, platform, e);
            }
        }
        log.info("多平台订单同步完成 shopId={}：成功 {} 失败 {} 入库 {}",
                shopId, succeeded, failedPlatforms.size(), inserted);
        return new OrderSyncSummary(3, succeeded, failedPlatforms.size(), inserted,
                List.copyOf(failedPlatforms));
    }

    public int syncByPlatform(Long shopId, String platform) {
        List<com.amz.model.UnifiedOrder> fetched;
        switch (platform) {
            case "TEMU": fetched = temuClient.fetchRecentOrders(shopId); break;
            case "TIKTOK": fetched = tiktokClient.fetchRecentOrders(shopId); break;
            case "SHEIN": fetched = sheinClient.fetchRecentOrders(shopId); break;
            default: throw new AttrIsNullException("不支持的平台：" + platform);
        }
        Set<String> existingKeys = loadExistingOrderKeys(shopId, fetched);
        int inserted = 0;
        for (com.amz.model.UnifiedOrder o : fetched) {
            String key = dedupKey(o.getPlatform(), o.getPlatformOrderNo());
            if (key != null && existingKeys.contains(key)) continue;
            o.setUnifiedOrderNo("UO" + System.currentTimeMillis() + inserted);
            o.setCnyAmount(currencyConverter.toCny(o.getOriginalAmount(), o.getCurrency()));
            // B3：明细持久化（items_json），否则多商品订单落库仍只存首行
            o.setItemsJson(com.amz.model.UnifiedOrderItems.toJson(o.getItems()));
            unifiedOrderMapper.insert(o);
            if (key != null) existingKeys.add(key);
            inserted++;
        }
        return inserted;
    }

    /**
     * 已存在订单号的集合，按 (platform, platform_order_no) 判重。
     *
     * <p>§S33.108：查询必须带 shop_id。不同店铺可能在同一平台拿到同一
     * platform_order_no（同 §S33.91 的业务前提），不带 shop_id 时
     * 别的店已有的订单号会把本店订单判成「已存在」而静默跳过——
     * 本店订单永远不落库，且无日志无异常。
     *
     * <p>注意表上的 uk_platform_order (platform, platform_order_no) 同样不含
     * shop_id，因此「跳过」顺带掩盖了 insert 撞 1062 的那条路：
     * 真有两店共享同一订单号时整个平台的同步会被标为失败。
     * 去重的店铺维度在这里补上，唯一键的维度另需迁移（见 HANDOFF §S33.108）。
     */
    private Set<String> loadExistingOrderKeys(Long shopId, List<com.amz.model.UnifiedOrder> fetched) {
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
        dedupQuery.eq(com.amz.model.UnifiedOrder::getShopId, shopId)
                .in(com.amz.model.UnifiedOrder::getPlatformOrderNo, orderNoList);
        for (com.amz.model.UnifiedOrder exist : unifiedOrderMapper.selectList(dedupQuery)) {
            existingKeys.add(dedupKey(exist.getPlatform(), exist.getPlatformOrderNo()));
        }
        return existingKeys;
    }

    private String dedupKey(String platform, String platformOrderNo) {
        if (platformOrderNo == null || platformOrderNo.isEmpty()) return null;
        return platform + "|" + platformOrderNo;
    }

    public PageResult<com.amz.model.UnifiedOrder> listOrders(Long shopId, PageRequest page) {
        return pageOrders(shopId, null, page);
    }

    public PageResult<com.amz.model.UnifiedOrder> listByPlatform(Long shopId, String platform, PageRequest page) {
        return pageOrders(shopId, platform, page);
    }

    /**
     * 统一订单分页。
     * <p>
     * 以前是 {@code selectList} 不带 LIMIT：一张持续被同步写入的订单表会被整表读进内存，
     * 而且「确实没有更多」与「被数据库返回量截断」在响应里长得一模一样。
     * 排序键用 id（同步是按平台顺序批量插入的，id 单调），游标必须在查询之前解析，
     * 否则一个坏游标会先扫一遍库再报错。
     */
    private PageResult<com.amz.model.UnifiedOrder> pageOrders(Long shopId, String platform, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        Long cursorId = req.cursorId();
        LambdaQueryWrapper<com.amz.model.UnifiedOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(com.amz.model.UnifiedOrder::getShopId, shopId);
        if (platform != null && !platform.isBlank()) {
            wrapper.eq(com.amz.model.UnifiedOrder::getPlatform, platform);
        }
        if (cursorId != null) {
            wrapper.lt(com.amz.model.UnifiedOrder::getId, cursorId);
        }
        wrapper.orderByDesc(com.amz.model.UnifiedOrder::getId)
               .last("LIMIT " + req.probeSize());
        List<com.amz.model.UnifiedOrder> rows = unifiedOrderMapper.selectList(wrapper);
        for (com.amz.model.UnifiedOrder o : rows) {
            com.amz.model.UnifiedOrderItems.ensureItems(o);
        }
        return PageResult.of(rows, req.size(), o -> PageRequest.encodeCursor(o.getId()));
    }

    public boolean markShipped(Long orderId, String trackingNo) {
        if (trackingNo == null || trackingNo.isBlank()) {
            throw new AttrIsNullException("运单号不能为空");
        }
        String tracking = trackingNo.trim();
        com.amz.model.UnifiedOrder order = unifiedOrderMapper.selectById(orderId);
        if (order == null) throw new AttrIsNullException("订单不存在：id=" + orderId);
        // 这一句会真的把发货回传给平台，所以归属必须按行严格判定（helper 里写了为什么不能用 lenient）
        requireShopOnRow(order.getShopId(), "统一订单");
        boolean ok;
        // shopId 必须传下去（§S33.92）：上面已经 selectById + requireShopOnRow 校验过归属，
        // 此时 order.getShopId() 是已知的。不传则客户端会回退到「该平台首个账号」，
        // 等于用任意一家店的凭证替这一家店发货。
        switch (order.getPlatform() == null ? "" : order.getPlatform()) {
            case "TEMU": ok = temuClient.markShipped(order.getShopId(), order.getPlatformOrderNo(), tracking); break;
            case "TIKTOK": ok = tiktokClient.markShipped(order.getShopId(), order.getPlatformOrderNo(), tracking); break;
            case "SHEIN": ok = sheinClient.markShipped(order.getShopId(), order.getPlatformOrderNo(), tracking); break;
            default: throw new AttrIsNullException("不支持的平台：" + order.getPlatform());
        }
        if (ok) {
            order.setTrackingNo(tracking);
            order.setStatus("SHIPPED");
            unifiedOrderMapper.updateById(order);
        }
        return ok;
    }

    /**
     * 按「行上的 shopId」判定归属，用于所有 id 定位的写操作。
     * <p>
     * 必须是严格版：{@code isShopAllowed} 在 userId 为空的上下文里会因为「没有授权列表」放行
     * （定时任务与内部调用要依赖那条分支），而这些方法都是浏览器可直接触发的写入。
     * 缺 shopId 的行同样按拒绝处理——归属缺失不等于不受限。
     */
    private void requireShopOnRow(Long shopId, String what) {
        if (!UserContext.isShopAllowedStrict(shopId)) {
            rejectForeign(shopId, what, null);
        }
    }

    /**
     * 统一的越权拒绝：抛业务异常而不是 {@code IllegalStateException}。
     * 后者会被全局兜底成 500「服务器内部错误」，被拒的一方拿不到结论，
     * 日志里也混在系统故障里看不出是越权拦截。
     */
    private void rejectForeign(Long shopId, String what, String detail) {
        log.warn("多平台越权拦截：userId={}, role={}, {}ShopId={}, {}",
                UserContext.getUserId(), UserContext.getRole(), what, shopId,
                detail == null ? "" : detail);
        throw new CodeErrorException(what + "不存在或无权访问");
    }

}
