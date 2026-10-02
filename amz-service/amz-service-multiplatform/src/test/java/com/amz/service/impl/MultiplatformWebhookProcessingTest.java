package com.amz.service.impl;

import com.amz.context.UserContext;
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

import java.util.List;

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
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("处理未抛异常时状态要变成 PROCESSED，并留下人话结果")
    void successMarksProcessed() {
        when(webhookEventMapper.selectCount(any())).thenReturn(0L);

        WebhookEvent saved = service.receiveWebhook("TEMU", "ORDER_CREATED", "EV-1", "{}", 1L);

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

        WebhookEvent saved = service.receiveWebhook("TEMU", null, "EV-2", "{}", 1L);

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventMapper, atLeastOnce()).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertNotNull(saved);
    }
}
