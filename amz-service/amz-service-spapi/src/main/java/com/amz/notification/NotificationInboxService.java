package com.amz.notification;

import com.amz.mapper.NotificationInboxMapper;
import com.amz.model.NotificationInboxEntity;
import com.amz.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * 入站通知落库服务：加密、哈希、按 {@code notification_id} 去重、定状态。
 * <p>
 * 这条链路的顺序是硬约束，不能调换：
 * <pre>
 *   拉取消息 -> 结构校验 -> 解析店铺 -> 【本服务落 Inbox】 -> 删除 SQS 消息
 * </pre>
 * 只有本服务返回「已持久化」之后才允许 ack / delete SQS 消息；
 * 落库失败必须保留消息让它重新可见，否则等于把 Amazon 的事件直接扔掉。
 * SQS 是 standard 队列（SP-API 不支持 FIFO），重复投递一定发生，
 * 因此重复由 {@code uk_notification_id} 吸收，而不是靠「先查再插」。
 * <p>
 * 日志只输出 notificationId / notificationType / shopId / payload_sha256 / 错误码，
 * 禁止输出 payload 明文——订单类通知含买家姓名与地址。
 */
@Slf4j
@Service
public class NotificationInboxService {

    /** 重复投递但载荷哈希不同：可能是 Amazon 侧重发了不同内容，必须留证并告警。 */
    public static final String ERR_DUPLICATE_DIFF_HASH = "DUPLICATE_DIFF_HASH";

    /** 载荷超过上限：原始载荷不入库，避免一次异常消息撑爆存储。 */
    public static final String ERR_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    /** 落库结果。 */
    public enum IngestResult {
        /** 首次投递，已落库等待处理。 */
        CREATED,
        /** 重复投递且载荷哈希一致：预期内的 SQS 重投，直接吸收。 */
        DUPLICATE_SAME_HASH,
        /** 重复投递但载荷哈希不同：异常，保留首条并告警。 */
        DUPLICATE_DIFF_HASH,
        /** 载荷超过 payload-max-bytes：记为 INVALID 且不存正文。 */
        TOO_LARGE
    }

    private final NotificationInboxMapper inboxMapper;
    private final CryptoUtil cryptoUtil;
    private final NotificationProperties properties;

    public NotificationInboxService(NotificationInboxMapper inboxMapper,
                                    CryptoUtil cryptoUtil,
                                    NotificationProperties properties) {
        this.inboxMapper = inboxMapper;
        this.cryptoUtil = cryptoUtil;
        this.properties = properties;
    }

    /**
     * 落库一条已通过结构校验的通知。
     *
     * @param validated 结构校验结果
     * @param rawJson   原始 JSON 正文（会被加密后落库，不落明文）
     * @param binding   subscriptionId 反查到的店铺绑定；null 表示无法归属店铺
     * @param synthetic 是否为合成演练事件
     * @return 落库结果；只有非 {@link IngestResult#TOO_LARGE} 的结果才表示事件已被安全持久化
     */
    public IngestResult ingest(ValidatedNotification validated,
                               String rawJson,
                               NotificationShopBinding binding,
                               boolean synthetic) {
        String hash = sha256(rawJson);
        int bytes = rawJson == null ? 0 : rawJson.getBytes(StandardCharsets.UTF_8).length;
        try {
            if (bytes > properties.getPayloadMaxBytes()) {
                insert(validated, null, hash, bytes, binding, synthetic,
                        NotificationInboxStatus.INVALID, ERR_PAYLOAD_TOO_LARGE,
                        overloadMessage(bytes));
                log.warn("[NotificationInbox] 载荷超限，已留证不存正文：notificationId={}, type={}, bytes={}, limit={}",
                        validated.notificationId(), validated.notificationType(), bytes,
                        properties.getPayloadMaxBytes());
                return IngestResult.TOO_LARGE;
            }
            String status = binding == null
                    ? NotificationInboxStatus.UNRESOLVED_SUBSCRIPTION
                    : NotificationInboxStatus.RECEIVED;
            insert(validated, rawJson, hash, bytes, binding, synthetic, status, null, null);
            return IngestResult.CREATED;
        } catch (DuplicateKeyException e) {
            return handleDuplicate(validated, hash);
        }
    }

    private void insert(ValidatedNotification validated,
                        String rawJson,
                        String hash,
                        int bytes,
                        NotificationShopBinding binding,
                        boolean synthetic,
                        String status,
                        String errorCode,
                        String errorMessage) {
        NotificationInboxEntity entity = new NotificationInboxEntity();
        entity.setNotificationId(validated.notificationId());
        entity.setNotificationType(validated.notificationType());
        entity.setPayloadVersion(validated.payloadVersion());
        entity.setEventTime(validated.eventTime());
        entity.setPublishTime(validated.publishTime());
        entity.setApplicationId(validated.applicationId());
        entity.setSubscriptionId(validated.subscriptionId());
        // 超限事件不存正文：rawJson 为 null 时连加密都不调用，避免为不落库的内容做无谓的密钥运算
        entity.setPayloadEncrypted(rawJson == null ? null : cryptoUtil.encrypt(rawJson));
        entity.setPayloadSha256(hash);
        entity.setPayloadBytes(bytes);
        entity.setStatus(status);
        entity.setAttemptCount(0);
        entity.setMaxAttempts(properties.getMaxAttempts());
        entity.setNextAttemptAt(LocalDateTime.now());
        entity.setDuplicateCount(0);
        entity.setSynthetic(synthetic ? 1 : 0);
        if (binding != null) {
            entity.setShopId(binding.shopId());
            entity.setMarketplaceId(binding.marketplaceId());
            entity.setDestinationId(binding.destinationId());
        }
        if (StringUtils.hasText(errorCode)) {
            entity.setLastErrorCode(errorCode);
            entity.setLastErrorMessage(errorMessage);
        }
        inboxMapper.insert(entity);
    }

    /**
     * 处理 {@code uk_notification_id} 冲突。
     * <p>
     * 只在冲突分支走查询，热路径（首次投递）永远只有一次 INSERT：
     * 先查再插在高并发下是典型 TOCTOU，两个实例同时查到「不存在」会双双插入，
     * 唯一键反而成了唯一能保证正确的那道防线。
     */
    private IngestResult handleDuplicate(ValidatedNotification validated, String hash) {
        NotificationInboxEntity existing = inboxMapper.selectOne(
                new LambdaQueryWrapper<NotificationInboxEntity>()
                        .eq(NotificationInboxEntity::getNotificationId, validated.notificationId()));
        if (existing == null) {
            // 报了唯一键冲突却查不到：并发窗口内记录状态已变化。
            // 保守按同哈希重复处理——事件至少已在库里出现过一次，不能误判为首次投递。
            log.warn("[NotificationInbox] 唯一键冲突但回查为空，按重复处理：notificationId={}",
                    validated.notificationId());
            return IngestResult.DUPLICATE_SAME_HASH;
        }
        boolean sameHash = hash.equals(existing.getPayloadSha256());
        NotificationInboxEntity patch = new NotificationInboxEntity();
        patch.setId(existing.getId());
        int base = existing.getDuplicateCount() == null ? 0 : existing.getDuplicateCount();
        patch.setDuplicateCount(base + 1);
        if (!sameHash) {
            patch.setLastErrorCode(ERR_DUPLICATE_DIFF_HASH);
            patch.setLastErrorMessage("同一 notification_id 重复投递但载荷哈希不同，保留首条正文并告警");
        }
        inboxMapper.updateById(patch);
        log.warn("[NotificationInbox] 重复投递：notificationId={}, type={}, hashMatch={}, duplicateCount={}",
                validated.notificationId(), validated.notificationType(), sameHash, base + 1);
        return sameHash ? IngestResult.DUPLICATE_SAME_HASH : IngestResult.DUPLICATE_DIFF_HASH;
    }

    private String overloadMessage(int bytes) {
        return String.format("原始载荷 %d 字节，超过上限 %d 字节；正文未入库以避免撑爆存储，可在 SQS 原始消息中取证。",
                bytes, properties.getPayloadMaxBytes());
    }

    /** SHA-256 十六进制小写摘要，用于识别「同号不同内容」的重投。 */
    static String sha256(String text) {
        if (text == null) {
            text = "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 在当前 JVM 上不可用，无法保证重复投递可识别", e);
        }
    }
}