package com.amz.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SP-API 通知入站配置（前缀 {@code spapi.notifications}）。
 * <p>
 * 设计约束（不可软化）：
 * <ul>
 *   <li>{@code source} 只接受 {@code mock} 与 {@code sqs}。mock 使用合成事件，仅用于离线开发、
 *       回归与演练；生产必须为 sqs。</li>
 *   <li>队列只能是 SQS <b>standard</b> 队列：SP-API 不支持 FIFO 投递，因此不去实现按
 *       amazonOrderId 分组的顺序保证，业务层必须自己处理乱序（见 Handler 的 eventTime 比较）。</li>
 *   <li>SQS 消费必须使用独立于店铺凭证的 IAM 角色；
 *       {@code use-shop-static-credentials=true} 会被启动校验拒绝，禁止拿
 *       {@code amz_shop_credential.access_key_encrypted / secret_key_encrypted} 消费队列。</li>
 *   <li>{@code lease-timeout-seconds} 必须 <b>不早于</b> {@code visibility-timeout-seconds}。
 *       反过来的后果：租期先到期、消息仍在 SQS 不可见窗口内，第二个 Worker 能合法领取同一条
 *       Inbox 记录，形成并发重复处理。lease 更长时，即便 SQS 提前重投，重投也会被
 *       {@code uk_notification_id} 去重吸收，不会重复执行业务副作用。</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "spapi.notifications")
public class NotificationProperties {

    /** 通知入站总开关；关闭时不拉取也不消费。 */
    private boolean enabled = true;

    /** 事件源：{@code mock}（合成事件）或 {@code sqs}（真实队列）。 */
    private String source = "mock";

    /** SQS 队列 URL；source=sqs 时必填，且不得为 .fifo 队列。 */
    private String queueUrl = "";

    /** SQS 所在 AWS region；source=sqs 时必填。 */
    private String region = "";

    /**
     * 消费队列用的 IAM 角色 ARN（通过 STS AssumeRole 获取临时凭证）。
     * 留空时回退到 AWS 默认凭证链（IRSA / 实例角色 / 环境变量），
     * 但无论哪条路径都不得使用店铺静态 AWS 密钥。
     */
    private String stsRoleArn = "";

    /**
     * 禁止项：true 表示要用 {@code amz_shop_credential} 里的店铺静态 AWS 密钥消费 SQS。
     * 该用法会被 {@link NotificationStartupCheck} 拒绝启动，此字段仅为让非法配置可见而存在。
     */
    private boolean useShopStaticCredentials = false;

    /** SQS 长轮询等待秒数（0-20）。 */
    private int waitTimeSeconds = 20;

    /** 单次 ReceiveMessage 最大消息数（1-10）。 */
    private int maxMessages = 10;

    /** SQS 可见性超时秒数；必须不小于处理 接收消息 -> 落 Inbox -> 删消息 的耗时（下限 30 秒）。 */
    private int visibilityTimeoutSeconds = 60;

    /** 单轮 Worker 最多领取的事件数。 */
    private int batchSize = 10;

    /** 单条事件最大处理次数，超过进 DLQ。 */
    private int maxAttempts = 5;

    /** 重试基础退避秒数（指数退避）。 */
    private long baseDelaySeconds = 30;

    /** Inbox 领取租期秒数；必须 >= visibility-timeout-seconds，否则会并发重复处理同一条记录。 */
    private long leaseTimeoutSeconds = 300;

    /** 单条原始通知的最大字节数，超过视为 payload_too_large。 */
    private int payloadMaxBytes = 262144;

    /** 是否启用订阅对账调度（对比 SP-API 订阅与本地映射表）。 */
    private boolean subscriptionSyncEnabled = true;

    /** mock 事件源场景名，仅 source=mock 生效。 */
    private String mockScenario = "order-change";
}
