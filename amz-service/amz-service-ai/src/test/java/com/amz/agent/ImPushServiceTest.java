package com.amz.agent;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IM 推送服务测试（HTTP 全 mock，不出网）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IM 推送服务测试")
class ImPushServiceTest {

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private okhttp3.Call call;

    @InjectMocks
    private ImPushService imPushService;

    private static Response okResponse() {
        return new Response.Builder()
                .request(new Request.Builder().url("http://localhost/hook").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("ok", MediaType.get("application/json")))
                .build();
    }

    private void webhook(String url, String kind) {
        ReflectionTestUtils.setField(imPushService, "webhookUrl", url);
        ReflectionTestUtils.setField(imPushService, "webhookKind", kind);
    }

    @Test
    @DisplayName("飞书/企微/generic 三种载荷形状正确")
    void testPayloadShapes() {
        assertEquals(
                "{\"msg_type\":\"text\",\"content\":{\"text\":\"hi\"}}",
                ImPushService.buildPayload("feishu", "hi"));
        assertEquals(
                "{\"msgtype\":\"text\",\"text\":{\"content\":\"hi\"}}",
                ImPushService.buildPayload("wecom", "hi"));
        assertEquals("{\"text\":\"hi\"}", ImPushService.buildPayload("generic", "hi"));
        // 未知 kind 回退 generic；大小写不敏感
        assertEquals("{\"text\":\"hi\"}", ImPushService.buildPayload("dingtalk", "hi"));
        assertEquals(
                "{\"msg_type\":\"text\",\"content\":{\"text\":\"hi\"}}",
                ImPushService.buildPayload("  FeiShu ", "hi"));
    }

    @Test
    @DisplayName("引号/换行/反斜杠应正确转义")
    void testEscape() {
        assertEquals(
                "{\"text\":\"a\\\"b\\\\c\\nd\"}",
                ImPushService.buildPayload("generic", "a\"b\\c\nd"));
    }

    @Test
    @DisplayName("URL 为空时直接返回 0 且不发起 HTTP")
    void testBlankUrlSkips() {
        webhook("  ", "feishu");

        assertEquals(0, imPushService.pushAll(List.of("a", "b")));
        verify(okHttpClient, org.mockito.Mockito.never()).newCall(any());
    }

    @Test
    @DisplayName("全部成功返回条数；单条 HTTP 失败不阻断后续")
    void testPartialFailureContinues() throws Exception {
        webhook("http://localhost/hook", "feishu");
        okhttp3.Call failCall = org.mockito.Mockito.mock(okhttp3.Call.class);
        when(failCall.execute()).thenThrow(new java.io.IOException("boom"));
        when(call.execute()).thenReturn(okResponse());
        // 第一条走 mock call（成功），第二条走 failCall
        when(okHttpClient.newCall(any())).thenReturn(call, failCall);

        // 注意顺序：pushAll 按列表顺序调用，成功1条返回 1
        assertEquals(1, imPushService.pushAll(List.of("ok-msg", "bad-msg")));
        verify(okHttpClient, org.mockito.Mockito.times(2)).newCall(any());
    }

    @Test
    @DisplayName("空列表直接返回 0")
    void testEmptyList() {
        webhook("http://localhost/hook", "feishu");
        assertEquals(0, imPushService.pushAll(List.of()));
        assertEquals(0, imPushService.pushAll(null));
    }
}
