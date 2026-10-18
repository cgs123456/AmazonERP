package com.amz.service.impl;

import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.mapper.WebhookEventMapper;
import com.amz.model.PlatformAccount;
import com.amz.model.WebhookEvent;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台回调（Webhook）事件的接收与查询。
 *
 * <p>从 {@link MultiplatformServiceImpl} 拆出（§S33.90 偿还 §S33.88 记录的技术债）：
 * webhook 是<b>唯一不带用户 JWT</b> 的入口（网关与 {@code BaseAuthInterceptor} 双双放行
 * {@code /multiplatform/webhook}），鉴权完全靠本类自带的 HMAC-SHA256 验签，
 * 且它自己决定事件归属哪个店铺。把它从二十多个方法的大服务里独立出来，
 * 是为了让这条「无用户上下文的写路径」的边界一目了然——
 * 归属判定、验签拒绝、幂等去重、状态迁移四件事都收在这里，不再和账号/商品/消息逻辑混排。
 *
 * <p><b>分页三件套与 MultiplatformServiceImpl 的同名实现刻意重复</b>：
 * {@code timeCursor}/{@code cursorPayload}/{@code before} 在消息、库存快照、Webhook
 * 三个列表里各出现一次。那三个调用方位于不同生命周期、无共享状态，
 * 按 CLAUDE.md「抽象延迟到第二个真实用例」——这里是第一处抽到独立类，
 * 尚不构成必须共享的证据；哪天出现第四处且口径确有分化，再抽公共组件。
 *
 * <p>安全边界按 fail-closed：验签失败、无法确定归属、签名缺字段都显式拒绝，
 * 绝不「尽量记下来」。
 */
@Slf4j
@Service
public class WebhookEventService {

    /**
     * 平台回调验签密钥：未配置的平台一律拒绝回调（显式拒绝优于假成功）。
     * <p>
     * 注意它是<b>平台级</b>而非店铺级：三个平台共用一个配置值，
     * 所以签名里不含任何可以区分「哪个店铺」的信息——这正是
     * {@link #resolveShopIdByPlatform} 必须 fail-closed 的原因。
     */
    @Value("${multiplatform.webhook.secret.temu:}")
    private String temuWebhookSecret;

    @Value("${multiplatform.webhook.secret.tiktok:}")
    private String tiktokWebhookSecret;

    @Value("${multiplatform.webhook.secret.shein:}")
    private String sheinWebhookSecret;

    @Autowired
    private WebhookEventMapper webhookEventMapper;

    @Autowired
    private PlatformAccountMapper platformAccountMapper;

    /**
     * 接收平台回调。
     * <p>
     * <b>shopId 参数由控制器恒传 {@code null}</b>：webhook 在免认证白名单里，
     * 没有用户上下文，没有 {@code @ShopScoped} 可依附。所以归属只能由
     * {@link #resolveShopIdByPlatform} 反查。参数保留形式，是为了将来平台侧
     * 能提供「带店铺标识且被签名覆盖」的字段时有入口可接；在那之前任何
     * 调用方都不该往里塞值——验签只覆盖 {@code payload}，未签名字段可被伪造。
     *
     * <p><b>刻意不加「回调带 shopId」查询参数</b>：验签只对 payload 做 HMAC，
     * shopId 若走查询参数就是<b>未签名字段</b>，任何能构造合法签名的人都能把回调
     * 伪造成别的店铺，构成跨租户写入。真要支持同平台多店铺回调，
     * 需平台侧提供带店铺标识、且被签名字段——那是接入方案变更，不是这里能补的。
     *
     * @return 入库并处理完成的事件；{@code eventId} 命中幂等去重时返回 {@code null}
     */
    @Transactional
    public WebhookEvent receiveWebhook(String platform, String eventType, String eventId, String payload,
                                       Long shopId, String signature) {
        // 验签先于一切副作用：密钥未配置、缺签名头、签名不匹配都点名拒绝，
        // 不让未签名的回调消耗幂等查询或落库（显式拒绝优于假成功）。
        requireValidWebhookSignature(platform, payload, signature);
        // 幂等去重
        if (eventId != null && !eventId.isEmpty()) {
            LambdaQueryWrapper<WebhookEvent> dupCheck = new LambdaQueryWrapper<>();
            dupCheck.eq(WebhookEvent::getEventId, eventId);
            if (webhookEventMapper.selectCount(dupCheck) > 0) {
                log.info("Webhook 重复事件：platform={} eventType={} eventId={}", platform, eventType, eventId);
                return null;
            }
        }

        // shopId 未传时从平台账号表反查；仍为空则拒绝落库，避免产生无归属脏数据
        if (shopId == null) {
            shopId = resolveShopIdByPlatform(platform);
        }
        if (shopId == null) {
            // 2026-10-07 demo 档回环验收发现：这是可预期的业务状态（平台账号尚未录入，
            // 生产首次接入即如此），此前抛 IllegalStateException 被全局处理器兜底成
            // 「服务器内部错误」，回调方看不到真实原因。与验签拒绝同款走业务异常。
            throw new CodeErrorException(
                    "Webhook 无法确定归属店铺：平台 " + platform + " 尚未录入可用店铺账号"
                    + "（amz_platform_account 无 ACTIVE 记录），拒绝落库");
        }

        WebhookEvent event = new WebhookEvent();
        event.setShopId(shopId);
        event.setPlatform(platform);
        event.setEventType(eventType);
        event.setEventId(eventId);
        event.setPayload(payload);
        event.setStatus("RECEIVED");
        event.setCreateTime(LocalDateTime.now());
        webhookEventMapper.insert(event);

        // 同步执行处理并落最终状态
        try {
            handleWebhookEvent(event);
            // 原来只在异常分支改 status，成功路径把 RECEIVED 又写回去一遍：
            // DDL 里的 PROCESSED 因此永远不会出现，按状态筛选/统计都会以为事件还堵着。
            event.setStatus("PROCESSED");
            event.setProcessResult("已记录；当前事件分发只写日志，不触发业务动作");
        } catch (Exception e) {
            log.error("Webhook 事件处理失败 id={}", event.getId(), e);
            event.setStatus("FAILED");
            event.setProcessResult(e.getMessage());
        }
        event.setProcessTime(LocalDateTime.now());
        webhookEventMapper.updateById(event);
        return event;
    }

    /**
     * 平台侧回调未带 shopId 时，按平台反查唯一归属店铺。<b>无法唯一确定时抛业务异常，绝不猜。</b>
     * <p>
     * §S33.88：旧实现是 {@code orderByDesc(createTime).last("LIMIT 1")} 取「该平台最新
     * 创建的账号」，等于把回调硬塞给任意一个店铺。这在本仓是<b>必然</b>发生的错，不是
     * 边角情况：
     * <ul>
     *   <li>{@code uk_shop_platform (shop_id, platform)} 允许同一平台挂多个店铺，
     *       合成数据里 SHOPIFY 就同时属于 4 个店铺；</li>
     *   <li>验签密钥是<b>平台级</b>（{@code webhook.secret.temu} 一个值），不是店铺级，
     *       所以签名里不含任何可以区分店铺的信息；</li>
     *   <li>控制器传进来的 shopId 恒为 {@code null}（webhook 是免认证白名单入口，
     *       没有用户上下文）——反查是唯一路径，不是兜底。</li>
     * </ul>
     * 后果是跨租户数据污染：A 店的订单事件记到 B 店名下，A 店运营看不到自己的回调，
     * 而两边都收不到任何错误——看起来完全成功。
     * <p>
     * {@code LIMIT 2} 不是随意取值：取 2 行足以区分「0 个 / 1 个 / 多于 1 个」三种情况，
     * 同时保持有界读（不为枚举而全表扫描）。多于 1 个时拒绝落库，而不是按创建时间
     * 挑一个——挑中概率 1/N，且猜错了没有任何信号。
     * <p>
     * INACTIVE/ERROR 账号不参与：否则已停用的店还会接走回调（§S33.88 修的第二处）。
     */
    private Long resolveShopIdByPlatform(String platform) {
        List<PlatformAccount> accounts = platformAccountMapper.selectList(
                new LambdaQueryWrapper<PlatformAccount>()
                        .eq(PlatformAccount::getPlatform, platform)
                        .eq(PlatformAccount::getStatus, "ACTIVE")
                        .orderByDesc(PlatformAccount::getCreateTime)
                        .last("LIMIT 2"));
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        if (accounts.size() > 1) {
            throw new CodeErrorException("Webhook 无法确定归属店铺：平台 " + platform
                    + " 有多个 ACTIVE 店铺账号，而回调不带可区分的店铺线索"
                    + "（验签只对 payload 做 HMAC，密钥是平台级）。"
                    + "多店铺共用同一平台是正常形态，拒绝落库以免把事件写进错误的店铺；"
                    + "要让这类回调可用，需平台侧提供带店铺标识、且被签名覆盖的回调字段");
        }
        Long resolved = accounts.get(0).getShopId();
        log.info("Webhook 未传 shopId，按 platform={} 唯一反查命中 → shopId={}", platform, resolved);
        return resolved;
    }

    private void handleWebhookEvent(WebhookEvent event) {
        // 根据事件类型分发处理
        switch (event.getEventType()) {
            case "ORDER_CREATED":
                log.info("Webhook ORDER_CREATED: platform={} eventId={}", event.getPlatform(), event.getEventId());
                break;
            case "INVENTORY_CHANGE":
                log.info("Webhook INVENTORY_CHANGE: platform={}", event.getPlatform(), event.getEventId());
                break;
            case "MESSAGE_NEW":
                log.info("Webhook MESSAGE_NEW: platform={}", event.getPlatform(), event.getEventId());
                break;
            default:
                log.info("Webhook UNHANDLED: type={}", event.getEventType());
        }
        event.setStatus("PROCESSED");
    }

    /**
     * 回调事件列表（管理端查询，需 {@code @ShopScoped} 由控制器保证）。
     * 时间列不唯一，游标必须是 (create_time, id) 复合键。
     */
    public PageResult<WebhookEvent> listWebhookEvents(Long shopId, String status, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        TimeCursor cursor = timeCursor(req, "create_time");
        LambdaQueryWrapper<WebhookEvent> wrapper = new LambdaQueryWrapper<>();
        if (shopId != null) wrapper.eq(WebhookEvent::getShopId, shopId);
        if (status != null && !status.isBlank()) wrapper.eq(WebhookEvent::getStatus, status);
        if (cursor != null) {
            before(wrapper, WebhookEvent::getCreateTime, WebhookEvent::getId, cursor);
        }
        wrapper.orderByDesc(WebhookEvent::getCreateTime)
               .orderByDesc(WebhookEvent::getId)
               .last("LIMIT " + req.probeSize());
        List<WebhookEvent> rows = webhookEventMapper.selectList(wrapper);
        return PageResult.of(rows, req.size(),
                e -> PageRequest.encodeCursor(cursorPayload(e.getCreateTime(), e.getId())));
    }

    /** 平台回调验签：密钥未配置 / 缺签名头 / 签名不匹配三类都拒绝，话术不泄露是哪一种。 */
    private void requireValidWebhookSignature(String platform, String payload, String signature) {
        String key = platform == null ? "" : platform.toLowerCase();
        String secret = switch (key) {
            case "temu" -> temuWebhookSecret;
            case "tiktok" -> tiktokWebhookSecret;
            case "shein" -> sheinWebhookSecret;
            default -> "";
        };
        if (secret == null || secret.isBlank()) {
            throw new CodeErrorException(
                    "Webhook 验签失败：平台 " + platform + " 未配置验签密钥（multiplatform.webhook.secret." + key + "）");
        }
        if (signature == null || signature.isBlank()) {
            throw new CodeErrorException("Webhook 验签失败：缺少 X-Signature 签名头");
        }
        String expected = hmacSha256Hex(secret, payload == null ? "" : payload);
        // 常量时间比较，防时序侧信道；大小写不敏感（部分平台发大写 hex）
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new CodeErrorException("Webhook 验签失败：签名不匹配");
        }
    }

    private static String hmacSha256Hex(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                sb.append(String.format("%02x", value));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    // ==================== 分页（本类私有副本，见类注释「刻意重复」） ====================

    /** 时间列不唯一，所以游标必须带 id；载荷是 {@code LocalDateTime|id}。 */
    private record TimeCursor(LocalDateTime time, long id) {
    }

    private static TimeCursor timeCursor(PageRequest page, String field) {
        if (page == null || !page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("分页游标非法：" + field + " 游标格式应为 时间|id");
        }
        try {
            return new TimeCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (java.time.format.DateTimeParseException | NumberFormatException e) {
            throw new InvalidParamException("分页游标非法：" + field + " 时间或 id 无法解析");
        }
    }

    private static String cursorPayload(LocalDateTime time, Long id) {
        if (time == null || id == null) {
            throw new InvalidParamException("分页游标生成失败：排序键缺失（time=" + time + ", id=" + id + "）");
        }
        return time.toString() + "|" + id;
    }

    /**
     * {@code (time, id) < (cursorTime, cursorId)}：先比较时间，同一时间戳内再按 id 往前推。
     * 只按时间翻页会在同一秒内多行时漏行或重复。
     */
    private static <T> void before(LambdaQueryWrapper<T> wrapper,
                                   SFunction<T, LocalDateTime> timeGet,
                                   SFunction<T, Long> idGet,
                                   TimeCursor cursor) {
        wrapper.and(q -> q.lt(timeGet, cursor.time())
                .or(inner -> inner.eq(timeGet, cursor.time()).lt(idGet, cursor.id())));
    }
}
