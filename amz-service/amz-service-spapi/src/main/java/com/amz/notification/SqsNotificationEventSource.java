package com.amz.notification;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 真实 SQS 事件源（AWS SDK v2）。
 * <p>
 * 这是「具备对接能力」的落点：{@code spapi.notifications.source=sqs} 且配置了
 * queue-url / region 之后，无需改任何业务代码即可切换到真实队列。
 * 在没有 Amazon 凭证的阶段它不会消费到任何消息——这是<b>期望状态</b>，不是缺陷；
 * 可用性的判定依据是 {@link NotificationSourceHealthIndicator} 与
 * {@code amz_spapi_notification_received_total} 是否真的在增长。
 * <p>
 * 硬规则（与 mock 事件源一致，切换事件源不改变语义）：
 * <ul>
 *   <li><b>只有成功落 Inbox 的消息才 acknowledge</b>：ack 就是 DeleteMessage，
 *       删掉一条没落库的消息等于永久丢弃 Amazon 的事件。</li>
 *   <li><b>队列只能是 standard 队列</b>：SP-API 不支持 FIFO 投递，
 *       因此不实现按 amazonOrderId 分组的顺序保证，乱序由 Handler 按 eventTime 处理。</li>
 *   <li><b>消费凭证必须独立于店铺凭证</b>：只允许 IAM 角色（默认凭证链 / STS AssumeRole），
 *       {@code use-shop-static-credentials=true} 由 {@link NotificationStartupCheck} 在启动期拒绝。</li>
 *   <li><b>正文为空的消息不返回也不删除</b>：它永远不可能通过结构校验，
 *       删除等于销毁证据。保留消息让它走队列自身的 RedrivePolicy（maxReceiveCount）
 *       进入 SQS 侧 DLQ，运维仍可看到原始报文。因此生产部署<b>必须</b>为队列配置
 *       RedrivePolicy，否则坏消息会无限重投。</li>
 * </ul>
 * 日志只输出条数、错误码与 AWS 异常类名，禁止输出消息正文——订单通知含买家 PII。
 */
@Slf4j
public class SqsNotificationEventSource implements NotificationEventSource, AutoCloseable {

    /** 事件源名称（与 {@code spapi.notifications.source} 取值一致）。 */
    public static final String SOURCE_NAME = "sqs";

    /** 正文为空的消息错误码：可被永久跳过，但绝不能被删除。 */
    public static final String ERR_EMPTY_BODY = "EMPTY_MESSAGE_BODY";

    /** DeleteMessageBatch 单次最多 10 条（AWS 硬限制）。 */
    public static final int DELETE_BATCH_LIMIT = 10;

    /** SQS 单次 ReceiveMessage 最多 10 条（AWS 硬限制）。 */
    public static final int MAX_RECEIVE_MESSAGES = 10;

    /** SQS 长轮询最大等待秒数（AWS 硬限制）。 */
    public static final int MAX_WAIT_TIME_SECONDS = 20;

    /** STS 会话名：出现在 CloudTrail 里，必须能定位到是本服务的消费行为。 */
    public static final String STS_SESSION_NAME = "amz-erp-notification-consumer";

    /** AssumeRole 申请的会话时长（秒）；到期由 SDK 自动续期。 */
    public static final int STS_SESSION_DURATION_SECONDS = 3600;

    private final SqsClient client;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    /**
     * 生产用构造：按配置自行创建 SQS 客户端。
     *
     * @throws IllegalStateException region 为空（配置错误，必须在启动期暴露）
     */
    public SqsNotificationEventSource(NotificationProperties properties, NotificationMetrics metrics) {
        this(createClient(properties), properties, metrics);
    }

    /** 测试用构造：注入客户端桩，验证 ReceiveMessage / DeleteMessage 参数与 visibility timeout。 */
    SqsNotificationEventSource(SqsClient client,
                               NotificationProperties properties,
                               NotificationMetrics metrics) {
        this.client = Objects.requireNonNull(client, "SqsClient");
        this.properties = Objects.requireNonNull(properties, "NotificationProperties");
        this.metrics = Objects.requireNonNull(metrics, "NotificationMetrics");
    }

    /**
     * 按配置创建 SQS 客户端。
     * <p>
     * region 必填：留空时 {@code Region.of("")} 会抛异常，但错误信息对用户不友好，
     * 这里显式拦下并说明这是配置错误而不是 SDK 缺陷。
     */
    public static SqsClient createClient(NotificationProperties properties) {
        Objects.requireNonNull(properties, "NotificationProperties");
        String region = properties.getRegion();
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "SQS 事件源缺少 spapi.notifications.region：无法建立客户端。"
                            + "请在部署清单中设置 SPAPI_NOTIFICATIONS_REGION（如 us-east-1）。");
        }
        String queueUrl = properties.getQueueUrl();
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException(
                    "SQS 事件源缺少 spapi.notifications.queue-url：无法建立客户端。"
                            + "请在部署清单中设置 SPAPI_NOTIFICATIONS_QUEUE_URL。");
        }
        return SqsClient.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(credentialsProvider(properties.getStsRoleArn(), region))
                .build();
    }

    /**
     * 凭证链：显式配置了 {@code sts-role-arn} 时走 STS AssumeRole，否则回退
     * AWS 默认凭证链（IRSA / 实例角色 / 环境变量）。
     * <p>
     * 两条路径拿到都是独立于店铺的角色凭证，都不会触碰
     * {@code amz_shop_credential} 里的店铺静态 AWS 密钥。
     * <p>
     * STS 客户端与 SQS 客户端共用同一个 region：STS 是区域性服务，
     * 用与队列相同的 region 也避免了「凭证能取到但队列在另一个区」这类半通状态。
     *
     * @throws IllegalStateException 配置了 role ARN 却没有 region
     */
    static AwsCredentialsProvider credentialsProvider(String stsRoleArn, String region) {
        if (stsRoleArn == null || stsRoleArn.isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "配置了 spapi.notifications.sts-role-arn 但缺少 region：无法创建 STS 客户端。");
        }
        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(StsClient.builder().region(Region.of(region.trim())).build())
                .refreshRequest(() -> AssumeRoleRequest.builder()
                        .roleArn(stsRoleArn.trim())
                        .roleSessionName(STS_SESSION_NAME)
                        .durationSeconds(STS_SESSION_DURATION_SECONDS)
                        .build())
                .asyncCredentialUpdateEnabled(false)
                .build();
    }

    @Override
    public List<NotificationEnvelope> poll(int maxMessages) {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(properties.getQueueUrl())
                .maxNumberOfMessages(clamp(maxMessages, 1, MAX_RECEIVE_MESSAGES))
                .waitTimeSeconds(clamp(properties.getWaitTimeSeconds(), 0, MAX_WAIT_TIME_SECONDS))
                .visibilityTimeout(properties.getVisibilityTimeoutSeconds())
                .build();
        ReceiveMessageResponse response = client.receiveMessage(request);
        List<Message> messages = response == null || response.messages() == null
                ? List.of()
                : response.messages();
        List<NotificationEnvelope> envelopes = new ArrayList<>(messages.size());
        int skipped = 0;
        for (Message message : messages) {
            String body = message.body();
            if (body == null || body.isBlank()) {
                // 不返回也不删除：空正文永远无法解析，删除即销毁证据，交给队列 RedrivePolicy 兜底。
                skipped++;
                metrics.invalid(ERR_EMPTY_BODY);
                continue;
            }
            String handle = message.receiptHandle();
            if (handle == null || handle.isBlank()) {
                skipped++;
                metrics.invalid(ERR_EMPTY_BODY);
                continue;
            }
            envelopes.add(new NotificationEnvelope(handle, body, false));
        }
        if (skipped > 0) {
            log.error("[SqsNotificationEventSource] 跳过 {} 条正文或回执句柄为空的消息："
                            + "它们不会被删除，将重投直至队列 RedrivePolicy 转入 SQS DLQ。"
                            + "请确认队列已配置 maxReceiveCount。", skipped);
        }
        return envelopes;
    }

    @Override
    public void acknowledge(List<String> receiptHandles) {
        if (receiptHandles == null || receiptHandles.isEmpty()) {
            return;
        }
        List<String> handles = receiptHandles.stream()
                .filter(h -> h != null && !h.isBlank())
                .toList();
        for (int start = 0; start < handles.size(); start += DELETE_BATCH_LIMIT) {
            List<String> chunk = handles.subList(start, Math.min(start + DELETE_BATCH_LIMIT, handles.size()));
            deleteChunk(chunk);
        }
    }

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException e) {
            log.warn("[SqsNotificationEventSource] 关闭 SQS 客户端失败：error={}", e.getClass().getSimpleName());
        }
    }

    private void deleteChunk(List<String> chunk) {
        List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            entries.add(DeleteMessageBatchRequestEntry.builder()
                    .id("msg-" + i)
                    .receiptHandle(chunk.get(i))
                    .build());
        }
        try {
            DeleteMessageBatchResponse response = client.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                    .queueUrl(properties.getQueueUrl())
                    .entries(entries)
                    .build());
            List<BatchResultErrorEntry> failed = response == null || response.failed() == null
                    ? List.of()
                    : response.failed();
            if (!failed.isEmpty()) {
                // 不抛异常：抛出去只会让调用方以为整批失败。消息未被删除意味着会被重投，
                // 重投由 uk_notification_id 去重吸收，业务副作用不会重复执行。
                metrics.sqsDeleteFailed();
                log.error("[SqsNotificationEventSource] DeleteMessageBatch 部分失败，未删除 {} 条，"
                                + "这些消息会被重投并由 Inbox 唯一键去重：failedIds={}",
                        failed.size(), failed.stream().map(BatchResultErrorEntry::id).toList());
            }
        } catch (SqsException e) {
            metrics.sqsDeleteFailed();
            log.error("[SqsNotificationEventSource] DeleteMessageBatch 调用失败，未删除 {} 条消息：error={}, "
                            + "awsErrorCode={}，消息将重投并由 Inbox 唯一键去重。",
                    chunk.size(), e.getClass().getSimpleName(), e.awsErrorDetails() == null
                            ? "unknown" : e.awsErrorDetails().errorCode());
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
