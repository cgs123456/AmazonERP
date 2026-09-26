package com.amz.notification;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SQS 事件源测试（AWS SDK 客户端为桩）。
 * <p>
 * 这个测试要证明的不是「AWS SDK 能用」——那是 AWS 自己的测试——而是：
 * <ol>
 *   <li>我们传给 SQS 的参数是对的（长轮询、可见性超时、批大小都在 AWS 允许范围内）。</li>
 *   <li>「只删已落库消息」这条规则在传输层真的成立：删除调用的次数与内容由上层决定，
 *       本类不做任何「顺手删掉」的动作。</li>
 *   <li>空正文消息不会被删除也不会被返回：它永远解析不了，删除即销毁证据。</li>
 * </ol>
 * 不覆盖：真实网络调用（见 {@code RealSqsIntegrationTest}，默认禁用）。
 */
class SqsNotificationEventSourceTest {

    private static final String QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/amz-erp-notifications";

    private SqsClient client;
    private NotificationProperties properties;
    private NotificationMetrics metrics;
    private SqsNotificationEventSource source;

    @BeforeEach
    void setUp() {
        client = mock(SqsClient.class);
        properties = new NotificationProperties();
        properties.setSource("sqs");
        properties.setQueueUrl(QUEUE_URL);
        properties.setRegion("us-east-1");
        properties.setWaitTimeSeconds(20);
        properties.setMaxMessages(10);
        properties.setVisibilityTimeoutSeconds(60);
        metrics = new NotificationMetrics(new SimpleMeterRegistry());
        source = new SqsNotificationEventSource(client, properties, metrics);
    }

    @Test
    @DisplayName("ReceiveMessage 使用长轮询、可见性超时与配置队列")
    void pollUsesLongPollingAndVisibilityTimeout() {
        givenMessages(message("h1", "{\"a\":1}"));

        List<NotificationEnvelope> envelopes = source.poll(10);

        assertEquals(1, envelopes.size());
        assertEquals("h1", envelopes.get(0).receiptHandle());
        // 真实 SQS 消息永远不是合成事件：synthetic 标记错了会让演练数据被当成经营数据。
        assertFalse(envelopes.get(0).synthetic());
        ReceiveMessageRequest request = capturedReceiveRequest();
        assertEquals(QUEUE_URL, request.queueUrl());
        assertEquals(10, request.maxNumberOfMessages());
        assertEquals(20, request.waitTimeSeconds());
        assertEquals(60, request.visibilityTimeout());
    }

    @Test
    @DisplayName("批大小与等待秒数被夹紧到 AWS 允许范围，避免请求被直接拒绝")
    void pollClampsToAwsLimits() {
        givenMessages();
        properties.setMaxMessages(999);
        properties.setWaitTimeSeconds(999);

        source.poll(999);

        ReceiveMessageRequest request = capturedReceiveRequest();
        assertEquals(SqsNotificationEventSource.MAX_RECEIVE_MESSAGES, request.maxNumberOfMessages());
        assertEquals(SqsNotificationEventSource.MAX_WAIT_TIME_SECONDS, request.waitTimeSeconds());
    }

    @Test
    @DisplayName("空队列返回空列表，不产生删除调用")
    void emptyQueueReturnsEmptyList() {
        givenMessages();

        assertTrue(source.poll(10).isEmpty());
        verify(client, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    @DisplayName("正文为空的消息既不返回也不删除：删除等于销毁无法解析的证据")
    void blankBodyIsSkippedAndNeverDeleted() {
        givenMessages(message("h1", "   "), message("h2", "{\"a\":1}"));

        List<NotificationEnvelope> envelopes = source.poll(10);

        assertEquals(1, envelopes.size());
        assertEquals("h2", envelopes.get(0).receiptHandle());
        assertEquals(1.0, metrics.counter("invalid", "errorCode",
                SqsNotificationEventSource.ERR_EMPTY_BODY).count());
        verify(client, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    @DisplayName("回执句柄为空的消息被跳过：没有句柄就无法 ack，纳入等于制造无限重投")
    void blankReceiptHandleIsSkipped() {
        givenMessages(message("", "{\"a\":1}"), message("h2", "{\"a\":1}"));

        assertEquals(1, source.poll(10).size());
        assertEquals(1.0, metrics.counter("invalid", "errorCode",
                SqsNotificationEventSource.ERR_EMPTY_BODY).count());
    }

    @Test
    @DisplayName("acknowledge 使用 DeleteMessageBatch 且带队列 URL")
    void acknowledgeDeletesBatchWithQueueUrl() {
        givenDeleteSuccess();

        source.acknowledge(List.of("h1", "h2"));

        DeleteMessageBatchRequest request = capturedDeleteRequest(0);
        assertEquals(QUEUE_URL, request.queueUrl());
        assertEquals(2, request.entries().size());
        assertEquals("h1", request.entries().get(0).receiptHandle());
        assertEquals("h2", request.entries().get(1).receiptHandle());
    }

    @Test
    @DisplayName("超过 10 条时拆批：DeleteMessageBatch 单次上限是 AWS 硬限制")
    void acknowledgeSplitsIntoBatchesOfTen() {
        givenDeleteSuccess();
        List<String> handles = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            handles.add("h" + i);
        }

        source.acknowledge(handles);

        verify(client, times(3)).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        assertEquals(10, capturedDeleteRequest(0).entries().size());
        assertEquals(10, capturedDeleteRequest(1).entries().size());
        assertEquals(3, capturedDeleteRequest(2).entries().size());
    }

    @Test
    @DisplayName("空 / null 回执列表不发起删除调用")
    void acknowledgeIgnoresEmptyInput() {
        source.acknowledge(List.of());
        source.acknowledge(null);

        verify(client, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    @DisplayName("删除部分失败：记录指标且不抛异常，消息重投由 Inbox 唯一键去重")
    void partialDeleteFailureIsRecordedAndSwallowed() {
        when(client.deleteMessageBatch(any(DeleteMessageBatchRequest.class))).thenReturn(
                DeleteMessageBatchResponse.builder()
                        .failed(BatchResultErrorEntry.builder().id("msg-0").code("ServiceUnavailable").build())
                        .build());

        source.acknowledge(List.of("h1", "h2"));

        assertEquals(1.0, metrics.counter("sqs.delete.failed").count());
    }

    @Test
    @DisplayName("删除调用抛 SqsException：记录指标且不向上抛，避免整批被误判失败")
    void deleteExceptionIsRecordedAndSwallowed() {
        when(client.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenThrow(SqsException.builder().message("boom").build());

        source.acknowledge(List.of("h1"));

        assertEquals(1.0, metrics.counter("sqs.delete.failed").count());
    }

    @Test
    @DisplayName("事件源名固定为 sqs，供日志与健康探针标识")
    void nameIsSqs() {
        assertEquals("sqs", source.name());
    }

    @Test
    @DisplayName("缺 region / queue-url 时建客户端直接失败，错误信息指向配置项本身")
    void clientCreationFailsClosedOnMissingConfig() {
        NotificationProperties noRegion = new NotificationProperties();
        noRegion.setQueueUrl(QUEUE_URL);
        noRegion.setRegion("");
        IllegalStateException regionError =
                assertThrows(IllegalStateException.class, () -> SqsNotificationEventSource.createClient(noRegion));
        assertTrue(regionError.getMessage().contains("SPAPI_NOTIFICATIONS_REGION"));

        NotificationProperties noQueue = new NotificationProperties();
        noQueue.setRegion("us-east-1");
        noQueue.setQueueUrl("  ");
        IllegalStateException queueError =
                assertThrows(IllegalStateException.class, () -> SqsNotificationEventSource.createClient(noQueue));
        assertTrue(queueError.getMessage().contains("SPAPI_NOTIFICATIONS_QUEUE_URL"));
    }

    @Test
    @DisplayName("未配置 sts-role-arn 时回退默认凭证链，绝不触碰店铺静态密钥")
    void credentialsProviderFallsBackToDefaultChain() {
        AwsCredentialsProvider provider = SqsNotificationEventSource.credentialsProvider("", "us-east-1");
        assertInstanceOf(DefaultCredentialsProvider.class, provider);
    }

    @Test
    @DisplayName("配置 sts-role-arn 且带 region 时走 STS AssumeRole")
    void credentialsProviderUsesStsWhenRoleArnConfigured() {
        AwsCredentialsProvider provider = SqsNotificationEventSource.credentialsProvider(
                "arn:aws:iam::123456789012:role/amz-erp-notification-consumer", "us-east-1");
        assertInstanceOf(StsAssumeRoleCredentialsProvider.class, provider);
    }

    @Test
    @DisplayName("配置了 role ARN 却没有 region：直接失败，避免半通状态")
    void credentialsProviderRequiresRegionWhenRoleArnConfigured() {
        assertThrows(IllegalStateException.class, () -> SqsNotificationEventSource.credentialsProvider(
                "arn:aws:iam::123456789012:role/amz-erp-notification-consumer", ""));
    }

    private void givenMessages(Message... messages) {
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(messages).build());
    }

    private void givenDeleteSuccess() {
        when(client.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());
    }

    private ReceiveMessageRequest capturedReceiveRequest() {
        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(captor.capture());
        return captor.getValue();
    }

    private DeleteMessageBatchRequest capturedDeleteRequest(int index) {
        ArgumentCaptor<DeleteMessageBatchRequest> captor =
                ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        // 不能用 times(index + 1)：Mockito 的 times(n) 校验的是「总调用数恰好为 n」，
        // 拆批场景下总调用数等于批次数，取第 0 批时会被判成「期望 1 次、实际 3 次」。
        // 这里要的是「按发生顺序取第 index 次调用」，因此用 atLeastOnce 收集全部再取下标。
        verify(client, atLeastOnce()).deleteMessageBatch(captor.capture());
        return captor.getAllValues().get(index);
    }

    private static Message message(String receiptHandle, String body) {
        return Message.builder().receiptHandle(receiptHandle).body(body).build();
    }
}