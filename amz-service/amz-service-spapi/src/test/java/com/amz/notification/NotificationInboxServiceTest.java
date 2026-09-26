package com.amz.notification;

import com.amz.mapper.NotificationInboxMapper;
import com.amz.model.NotificationInboxEntity;
import com.amz.util.CryptoUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站通知落库测试。
 * <p>
 * 这里盯的是最容易静默出错的三件事：
 * 重复投递被当成首次投递（副作用翻倍）、正文落明文（PII 泄露）、
 * 超限正文照单全收（一条异常消息撑爆存储）。
 */
class NotificationInboxServiceTest {

    private static final String RAW_JSON = """
            {
              "NotificationVersion": "2022-02-02",
              "NotificationType": "ORDER_CHANGE",
              "PayloadVersion": "2022-02-02",
              "EventTime": "2026-09-20T10:05:00.000Z",
              "Payload": {
                "OrderChangeNotification": {
                  "AmazonOrderId": "903-1000001-2000001",
                  "OrderStatus": "Shipped",
                  "BuyerName": "Zhang San"
                }
              },
              "NotificationMetadata": {
                "ApplicationId": "amzn1.sp.solution.00000000",
                "SubscriptionId": "SUB-1",
                "NotificationId": "NOTIF-1",
                "PublishTime": "2026-09-20T10:05:05.000Z"
              }
            }
            """;

    private NotificationInboxMapper mapper;
    private CryptoUtil cryptoUtil;
    private NotificationProperties properties;
    private NotificationMetrics metrics;
    private NotificationInboxService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationInboxMapper.class);
        cryptoUtil = mock(CryptoUtil.class);
                // 与 CryptoUtil 真实语义一致：null 进 null 出
        when(cryptoUtil.encrypt(any())).thenAnswer(i -> i.getArgument(0) == null ? null : "CIPHERTEXT");
        properties = new NotificationProperties();
        metrics = new NotificationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        properties.setPayloadMaxBytes(262144);
        properties.setMaxAttempts(5);
        service = new NotificationInboxService(mapper, cryptoUtil, properties, metrics);
    }

    @Test
    @DisplayName("首次投递：落库 RECEIVED，attempt=0，到期时间可用")
    void firstDeliveryCreatesReceivedRow() {
        NotificationInboxService.IngestResult result =
                service.ingest(notification(), RAW_JSON, binding(7L), false);

        assertEquals(NotificationInboxService.IngestResult.CREATED, result);
        NotificationInboxEntity saved = capturedInsert();
        assertEquals("NOTIF-1", saved.getNotificationId());
        assertEquals("ORDER_CHANGE", saved.getNotificationType());
        assertEquals(NotificationInboxStatus.RECEIVED, saved.getStatus());
        assertEquals(0, saved.getAttemptCount());
        assertEquals(5, saved.getMaxAttempts());
        assertEquals(0, saved.getDuplicateCount());
        assertEquals(0, saved.getSynthetic());
        assertEquals(7L, saved.getShopId());
        assertTrue(saved.getNextAttemptAt() != null
                && !saved.getNextAttemptAt().isAfter(LocalDateTime.now().plusSeconds(5)));
        verify(mapper, never()).updateById(any(NotificationInboxEntity.class));
    }

    @Test
    @DisplayName("正文只允许以密文落库，明文与 PII 不得出现在任何字段")
    void payloadIsStoredEncryptedOnly() {
        service.ingest(notification(), RAW_JSON, binding(7L), false);

        NotificationInboxEntity saved = capturedInsert();
        assertEquals("CIPHERTEXT", saved.getPayloadEncrypted());
        assertNotEquals(RAW_JSON, saved.getPayloadEncrypted());
        assertFalse(saved.getPayloadEncrypted().contains("Zhang San"));
        assertFalse(saved.getPayloadEncrypted().contains("903-1000001-2000001"));
        verify(cryptoUtil).encrypt(RAW_JSON);
    }

    @Test
    @DisplayName("payload_sha256 为 64 位小写十六进制，且随内容变化")
    void sha256IsStableHexAndContentSensitive() {
        service.ingest(notification(), RAW_JSON, binding(7L), false);
        String first = capturedInsert().getPayloadSha256();

        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"), "哈希必须是小写十六进制，实际：" + first);

        mapper = mock(NotificationInboxMapper.class);
        service = new NotificationInboxService(mapper, cryptoUtil, properties, metrics);
        service.ingest(notification(), RAW_JSON + " ", binding(7L), false);
        assertNotEquals(first, capturedInsert().getPayloadSha256());
    }

    @Test
    @DisplayName("同哈希重复投递：吸收，duplicate_count + 1，不产生第二条记录")
    void sameHashDuplicateIsAbsorbed() {
        when(mapper.insert(any(NotificationInboxEntity.class))).thenThrow(new DuplicateKeyException("uk_notification_id"));
        when(mapper.selectOne(any())).thenReturn(existing(RAW_JSON, 3));

        NotificationInboxService.IngestResult result =
                service.ingest(notification(), RAW_JSON, binding(7L), false);

        assertEquals(NotificationInboxService.IngestResult.DUPLICATE_SAME_HASH, result);
        ArgumentCaptor<NotificationInboxEntity> patch = ArgumentCaptor.forClass(NotificationInboxEntity.class);
        verify(mapper).updateById(patch.capture());
        assertEquals(4, patch.getValue().getDuplicateCount());
        assertNull(patch.getValue().getLastErrorCode());
    }

    @Test
    @DisplayName("异哈希重复投递：保留首条并告警，duplicate_count + 1")
    void differentHashDuplicateIsFlagged() {
        when(mapper.insert(any(NotificationInboxEntity.class))).thenThrow(new DuplicateKeyException("uk_notification_id"));
        when(mapper.selectOne(any())).thenReturn(existing("{\"NotificationId\":\"NOTIF-1\"}", 1));

        NotificationInboxService.IngestResult result =
                service.ingest(notification(), RAW_JSON, binding(7L), false);

        assertEquals(NotificationInboxService.IngestResult.DUPLICATE_DIFF_HASH, result);
        ArgumentCaptor<NotificationInboxEntity> patch = ArgumentCaptor.forClass(NotificationInboxEntity.class);
        verify(mapper).updateById(patch.capture());
        assertEquals(2, patch.getValue().getDuplicateCount());
        assertEquals(NotificationInboxService.ERR_DUPLICATE_DIFF_HASH, patch.getValue().getLastErrorCode());
    }

    @Test
    @DisplayName("历史行 duplicate_count 为空时按 0 处理，不能 NPE")
    void nullDuplicateCountIsTreatedAsZero() {
        when(mapper.insert(any(NotificationInboxEntity.class))).thenThrow(new DuplicateKeyException("uk_notification_id"));
        NotificationInboxEntity row = existing(RAW_JSON, 1);
        row.setDuplicateCount(null);
        when(mapper.selectOne(any())).thenReturn(row);

        service.ingest(notification(), RAW_JSON, binding(7L), false);

        ArgumentCaptor<NotificationInboxEntity> patch = ArgumentCaptor.forClass(NotificationInboxEntity.class);
        verify(mapper).updateById(patch.capture());
        assertEquals(1, patch.getValue().getDuplicateCount());
    }

    @Test
    @DisplayName("超限载荷：记为 INVALID 留证，正文不入库")
    void oversizedPayloadIsRecordedWithoutBody() {
        properties.setPayloadMaxBytes(64);

        NotificationInboxService.IngestResult result =
                service.ingest(notification(), RAW_JSON, binding(7L), false);

        assertEquals(NotificationInboxService.IngestResult.TOO_LARGE, result);
        NotificationInboxEntity saved = capturedInsert();
        assertNull(saved.getPayloadEncrypted());
        assertEquals(NotificationInboxStatus.INVALID, saved.getStatus());
        assertEquals(NotificationInboxService.ERR_PAYLOAD_TOO_LARGE, saved.getLastErrorCode());
        assertEquals(RAW_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                saved.getPayloadBytes());
        verify(cryptoUtil, never()).encrypt(any());
    }

    @Test
    @DisplayName("订阅无法解析店铺：状态为 UNRESOLVED_SUBSCRIPTION 且正文仍留证可重放")
    void unresolvedSubscriptionKeepsPayload() {
        NotificationInboxService.IngestResult result =
                service.ingest(notification(), RAW_JSON, null, false);

        assertEquals(NotificationInboxService.IngestResult.CREATED, result);
        NotificationInboxEntity saved = capturedInsert();
        assertEquals(NotificationInboxStatus.UNRESOLVED_SUBSCRIPTION, saved.getStatus());
        assertNull(saved.getShopId());
        assertEquals("CIPHERTEXT", saved.getPayloadEncrypted());
    }

    @Test
    @DisplayName("合成事件打标 synthetic=1，避免与真实事件混算")
    void syntheticFlagIsPersisted() {
        service.ingest(notification(), RAW_JSON, binding(7L), true);

        assertEquals(1, capturedInsert().getSynthetic());
    }

    @Test
    @DisplayName("唯一键冲突却回查为空：保守按重复处理，绝不退回 CREATED")
    void uniqueKeyConflictWithoutRowIsTreatedAsDuplicate() {
        when(mapper.insert(any(NotificationInboxEntity.class))).thenThrow(new DuplicateKeyException("uk_notification_id"));
        when(mapper.selectOne(any())).thenReturn(null);

        assertEquals(NotificationInboxService.IngestResult.DUPLICATE_SAME_HASH,
                service.ingest(notification(), RAW_JSON, binding(7L), false));
        verify(mapper, never()).updateById(any(NotificationInboxEntity.class));
    }

    private NotificationInboxEntity capturedInsert() {
        ArgumentCaptor<NotificationInboxEntity> captor = ArgumentCaptor.forClass(NotificationInboxEntity.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private static NotificationInboxEntity existing(String rawBody, int duplicateCount) {
        NotificationInboxEntity row = new NotificationInboxEntity();
        row.setId(100L);
        row.setNotificationId("NOTIF-1");
        row.setPayloadSha256(NotificationInboxService.sha256(rawBody));
        row.setDuplicateCount(duplicateCount);
        return row;
    }

    private static NotificationShopBinding binding(Long shopId) {
        return new NotificationShopBinding(shopId, "ATVPDKIKX0DER", "DEST-1");
    }

    private static ValidatedNotification notification() {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode payload = objectMapper.readTree(RAW_JSON).get("Payload");
            return new ValidatedNotification(
                    "NOTIF-1",
                    "ORDER_CHANGE",
                    "2022-02-02",
                    LocalDateTime.of(2026, 9, 20, 10, 5, 0),
                    LocalDateTime.of(2026, 9, 20, 10, 5, 5),
                    "amzn1.sp.solution.00000000",
                    "SUB-1",
                    payload,
                    RAW_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}