package com.amz.notification;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * 通知入站启动校验（fail-closed）。
 * <p>
 * 与 {@code ConnectorStartupCheck} 同一风格：配置错误在启动期抛 {@link IllegalStateException}，
 * 不允许带病启动。原因很直接——通知链路最容易出现的静默失败是：队列没接上、消息没人消费、
 * 页面显示「今日 0 条通知」，看上去和「今天真的没通知」完全一样。这类故障在生产上只能靠
 * 启动期拒绝来暴露，不能靠事后对账发现。
 * <p>
 * 校验逻辑本身是纯静态方法（不依赖 Spring 容器），便于单测与在任意生命周期复用；
 * Spring 生命周期回调只负责读 profile 并调用它。
 */
@Slf4j
@Component
public class NotificationStartupCheck {

    /** 生产 profile；与 mock 事件源同时出现视为配置错误。 */
    public static final String PROD_PROFILE = "prod";

    /** 离线样例数据事件源（合成事件，仅用于开发/回归/演练）。 */
    public static final String SOURCE_MOCK = "mock";

    /** 真实 SQS 事件源。 */
    public static final String SOURCE_SQS = "sqs";

    /** SQS 可见性超时下限（秒）：必须覆盖 接收 -> 落 Inbox -> 删消息 的窗口。 */
    public static final int MIN_VISIBILITY_TIMEOUT_SECONDS = 30;

    /** SQS 可见性超时上限（秒）：AWS 硬上限 12 小时。 */
    public static final int MAX_VISIBILITY_TIMEOUT_SECONDS = 43200;

    private final Environment environment;
    private final NotificationProperties properties;

    public NotificationStartupCheck(Environment environment, NotificationProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    /**
     * 启动自检入口（Spring 生命周期回调）。
     *
     * @throws IllegalStateException 任一硬规则不满足
     */
    @PostConstruct
    public void verify() {
        if (!properties.isEnabled()) {
            log.warn("[NotificationStartupCheck] spapi.notifications.enabled=false："
                    + "通知入站已整体关闭，不拉取也不消费任何事件。生产环境请勿长期保持该状态。");
            return;
        }
        String activeProfiles = String.join(",", environment.getActiveProfiles());
        try {
            validate(activeProfiles, properties);
        } catch (IllegalStateException e) {
            log.error("[NotificationStartupCheck] 通知入站启动校验失败，拒绝启动：{}", e.getMessage());
            throw e;
        }
        log.info("[NotificationStartupCheck] 启动校验通过：activeProfiles={}, source={}, queueConfigured={}。"
                        + "注意：校验通过只代表配置自洽，不代表已与 Amazon 联调成功。",
                activeProfiles, normalizeSource(properties),
                StringUtils.hasText(properties.getQueueUrl()));
    }

    /**
     * 校验通知入站配置（纯函数，无容器依赖）。
     *
     * @param activeProfiles 激活的 profile 串（逗号分隔），可为 null
     * @param props          通知配置，null 视为未配置
     * @throws IllegalStateException 任一硬规则不满足
     */
    public static void validate(String activeProfiles, NotificationProperties props) {
        if (props == null) {
            throw new IllegalStateException(
                    "通知入站启动校验失败：spapi.notifications 未配置，拒绝启动（fail-closed）。");
        }
        String source = normalizeSource(props);
        if (!SOURCE_MOCK.equals(source) && !SOURCE_SQS.equals(source)) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：spapi.notifications.source=%s 非法，只允许 mock 或 sqs。",
                    props.getSource()));
        }

        boolean prodActive = hasProfile(activeProfiles, PROD_PROFILE);
        if (prodActive && SOURCE_MOCK.equals(source)) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：profile=prod 时 spapi.notifications.source 不得为 mock。"
                            + "mock 使用合成事件，队列没接上会表现为「正常无通知」，无法区分故障与无业务，"
                            + "因此禁止在生产启用。activeProfiles=%s。",
                    activeProfiles));
        }

        if (SOURCE_SQS.equals(source)) {
            if (!StringUtils.hasText(props.getQueueUrl())) {
                throw new IllegalStateException(
                        "通知入站启动校验失败：source=sqs 时 spapi.notifications.queue-url 必填，拒绝启动。");
            }
            if (!StringUtils.hasText(props.getRegion())) {
                throw new IllegalStateException(
                        "通知入站启动校验失败：source=sqs 时 spapi.notifications.region 必填，拒绝启动。");
            }
            if (props.getQueueUrl().trim().endsWith(".fifo")) {
                throw new IllegalStateException(
                        "通知入站启动校验失败：SP-API 不支持 FIFO 队列投递，queue-url 不得为 .fifo 队列；"
                                + "顺序保证必须由业务层按 eventTime 自行处理。");
            }
            if (props.isUseShopStaticCredentials()) {
                throw new IllegalStateException(
                        "通知入站启动校验失败：spapi.notifications.use-shop-static-credentials=true 被拒绝。"
                                + "SQS 消费必须使用独立于店铺凭证的 IAM 角色，"
                                + "禁止复用 amz_shop_credential 中的店铺静态 AWS 密钥："
                                + "店铺密钥泄露会直接导致队列被任意消费与删除，且无法按队列维度回收权限。");
            }
        }

        if (props.getVisibilityTimeoutSeconds() < MIN_VISIBILITY_TIMEOUT_SECONDS) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：visibility-timeout-seconds=%d 小于最小值 %d。"
                            + "该值必须覆盖 接收消息 -> 落 Inbox -> 删除 SQS 消息 的全过程，"
                            + "过小会导致消息在持久化完成前被重新投递，形成重投风暴。",
                    props.getVisibilityTimeoutSeconds(), MIN_VISIBILITY_TIMEOUT_SECONDS));
        }
        if (props.getVisibilityTimeoutSeconds() > MAX_VISIBILITY_TIMEOUT_SECONDS) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：visibility-timeout-seconds=%d 超过 SQS 上限 %d。",
                    props.getVisibilityTimeoutSeconds(), MAX_VISIBILITY_TIMEOUT_SECONDS));
        }
        // 方向说明（spec 原文此处写反，已修正）：租期必须**不早于**可见性超时到期。
        // 若 lease < visibility，则事件仍在 SQS 不可见窗口内时租期就已过期，
        // 第二个 Worker 可以合法领取同一条 Inbox 记录并处理，形成并发重复处理。
        // 反之 lease >= visibility 时，即使 SQS 提前重投，重投方也领不到租期，最多被去重丢弃。
        if (props.getLeaseTimeoutSeconds() < props.getVisibilityTimeoutSeconds()) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：lease-timeout-seconds=%d 必须 >= visibility-timeout-seconds=%d，"
                            + "否则事件仍处于 SQS 不可见窗口时租期已过期，第二个 Worker 可领取同一条 Inbox 记录，"
                            + "造成同一事件被并发重复处理。",
                    props.getLeaseTimeoutSeconds(), props.getVisibilityTimeoutSeconds()));
        }
        if (props.getMaxAttempts() < 1) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：max-attempts=%d 必须 >= 1。", props.getMaxAttempts()));
        }
        if (props.getMaxMessages() < 1 || props.getMaxMessages() > 10) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：max-messages=%d 超出 SQS 允许范围 1-10。", props.getMaxMessages()));
        }
        if (props.getWaitTimeSeconds() < 0 || props.getWaitTimeSeconds() > 20) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：wait-time-seconds=%d 超出 SQS 长轮询范围 0-20。",
                    props.getWaitTimeSeconds()));
        }
        if (props.getPayloadMaxBytes() <= 0) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：payload-max-bytes=%d 必须 > 0。", props.getPayloadMaxBytes()));
        }
        if (props.getBatchSize() < 1) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：batch-size=%d 必须 >= 1。", props.getBatchSize()));
        }
        if (props.getBaseDelaySeconds() <= 0) {
            throw new IllegalStateException(String.format(
                    "通知入站启动校验失败：base-delay-seconds=%d 必须 > 0。", props.getBaseDelaySeconds()));
        }
    }

    /**
     * 校验并归一化 source（去空白、转小写）。用于组件构造期统一取值。
     */
    public static String normalizeSource(NotificationProperties props) {
        return props.getSource() == null ? "" : props.getSource().trim().toLowerCase();
    }

    private static boolean hasProfile(String activeProfiles, String target) {
        if (activeProfiles == null) {
            return false;
        }
        return Arrays.stream(activeProfiles.split(","))
                .map(String::trim)
                .anyMatch(p -> p.equalsIgnoreCase(target));
    }
}