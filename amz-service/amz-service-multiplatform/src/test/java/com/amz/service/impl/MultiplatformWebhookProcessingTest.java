package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.mapper.WebhookEventMapper;
import com.amz.model.WebhookEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook 事件落库后的状态迁移。
 *
 * <p>动因：DDL 写的是 {@code RECEIVED/PROCESSED/FAILED}，而实现只在异常分支改 status，
 * 成功路径把 {@code RECEIVED} 原样写回——于是 {@code PROCESSED} 永远不会出现，
 * 按状态筛选或做统计的页面会以为所有事件都还堵着。这条差异不体现在任何报错上，
 * 只能靠断言钉住。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Webhook 事件状态迁移")
class MultiplatformWebhookProcessingTest {

    @Mock
    private WebhookEventMapper webhookEventMapper;

    @Mock
    private PlatformAccountMapper platformAccountMapper;

    @InjectMocks
    private MultiplatformServiceImpl service;

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        ReflectionTestUtils.setField(service, "temuWebhookSecret", "test-secret");
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("处理未抛异常时状态要变成 PROCESSED，并留下人话结果")
    void successMarksProcessed() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);

        WebhookEvent saved = service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-1", "{}", 1L, sign("{}"));

        assertNotNull(saved);
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventMapper, atLeastOnce()).updateById(captor.capture());
        WebhookEvent finalState = captor.getValue();
        assertEquals("PROCESSED", finalState.getStatus());
        assertNotNull(finalState.getProcessResult());
        assertNotNull(finalState.getProcessTime());
        // 幂等：同 eventId 第二次直接返回 null，不重复落库
        verify(webhookEventMapper).insert(any(WebhookEvent.class));
    }

    @Test
    @DisplayName("事件类型为空时按失败记录，而不是把异常抛回平台回调方")
    void handlerFailureMarksFailed() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);

        WebhookEvent saved = service.receiveWebhook("TEMU", null, "EV-2", "{}", 1L, sign("{}"));

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventMapper, atLeastOnce()).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertNotNull(saved);
    }

    @Test
    @DisplayName("缺签名头 → 验签拒绝，不做幂等查询也不落库")
    void missingSignatureRejected() {
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-3", "{}", 1L, null));
        assertEquals("Webhook 验签失败：缺少 X-Signature 签名头", ex.getMessage());
        verify(webhookEventMapper, never()).insert(any(WebhookEvent.class));
    }

    @Test
    @DisplayName("签名不匹配 → 验签拒绝")
    void wrongSignatureRejected() {
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-4", "{}", 1L, "deadbeef"));
        assertEquals("Webhook 验签失败：签名不匹配", ex.getMessage());
        verify(webhookEventMapper, never()).insert(any(WebhookEvent.class));
    }

    @Test
    @DisplayName("未配置密钥的平台 → fail-closed 拒绝（显式拒绝优于假成功）")
    void unconfiguredPlatformRejected() {
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.receiveWebhook("AMAZON", "ORDER_CREATED", "EV-5", "{}", 1L, sign("{}")));
        assertEquals("Webhook 验签失败：平台 AMAZON 未配置验签密钥（multiplatform.webhook.secret.amazon）",
                ex.getMessage());
        verify(webhookEventMapper, never()).insert(any(WebhookEvent.class));
    }
}
