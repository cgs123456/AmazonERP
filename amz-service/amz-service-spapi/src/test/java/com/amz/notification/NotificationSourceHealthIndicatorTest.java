package com.amz.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationSourceHealthIndicatorTest {

    private NotificationProperties properties;
    private Environment environment;
    private NotificationSourceHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        indicator = new NotificationSourceHealthIndicator(properties, environment);
    }

    @Test
    @DisplayName("链路未启用时 UP，但必须写明「不代表已接通」，避免被当成验收证据")
    void upWhenDisabled() {
        properties.setEnabled(false);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(false, health.getDetails().get("enabled"));
        assertTrue(String.valueOf(health.getDetails().get("note")).contains("不代表已接通"));
    }

    @Test
    @DisplayName("SQS 源缺 queue-url / region 是配置错误，必须 DOWN")
    void downWhenSqsMisconfigured() {
        properties.setEnabled(true);
        properties.setSource("sqs");
        properties.setQueueUrl("");
        properties.setRegion("us-east-1");

        assertEquals(Status.DOWN, indicator.health().getStatus());

        properties.setQueueUrl("https://sqs.us-east-1.amazonaws.com/123/queue");
        properties.setRegion("");
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    @DisplayName("合成事件源在非生产环境可用但必须降级标注，不能看起来像真实数据")
    void degradedWhenMockSource() {
        properties.setEnabled(true);
        properties.setSource("mock");

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(true, health.getDetails().get("degraded"));
        assertTrue(String.valueOf(health.getDetails().get("reason")).contains("合成事件源"));
    }

    @Test
    @DisplayName("生产 profile 用合成事件源直接 DOWN：这条组合在生产等于造假数据")
    void downWhenMockSourceInProd() {
        properties.setEnabled(true);
        properties.setSource("mock");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    @DisplayName("启用后从未成功拉取：降级但不 DOWN，避免把整个服务从负载均衡摘掉")
    void degradedWhenNeverPolled() {
        properties.setEnabled(true);
        properties.setSource("sqs");
        properties.setQueueUrl("https://sqs.us-east-1.amazonaws.com/123/queue");
        properties.setRegion("us-east-1");

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus(), "通知是旁路能力，不能因此让 readiness 失败");
        assertEquals(true, health.getDetails().get("degraded"));
        assertTrue(String.valueOf(health.getDetails().get("reason")).contains("尚未成功拉取"));
    }

    @Test
    @DisplayName("成功拉取后恢复为不降级")
    void healthyAfterSuccessfulPoll() {
        properties.setEnabled(true);
        properties.setSource("sqs");
        properties.setQueueUrl("https://sqs.us-east-1.amazonaws.com/123/queue");
        properties.setRegion("us-east-1");

        indicator.recordPollSuccess(5);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(null, health.getDetails().get("degraded"));
        assertEquals(5L, health.getDetails().get("lastPolledCount"));
    }

    @Test
    @DisplayName("连续拉取失败降级，并带上错误摘要")
    void degradedAfterPollFailure() {
        properties.setEnabled(true);
        properties.setSource("sqs");
        properties.setQueueUrl("https://sqs.us-east-1.amazonaws.com/123/queue");
        properties.setRegion("us-east-1");

        indicator.recordPollFailure("AccessDenied");
        indicator.recordPollFailure("AccessDenied");
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(true, health.getDetails().get("degraded"));
        assertEquals(2L, health.getDetails().get("consecutiveFailures"));
        assertTrue(String.valueOf(health.getDetails().get("reason")).contains("AccessDenied"));

        indicator.recordPollSuccess(1);
        assertEquals(null, indicator.health().getDetails().get("degraded"));
    }

    @Test
    @DisplayName("存在未解析订阅积压时降级：这是「有事件但归不了店铺」的唯一外部可见信号")
    void degradedWhenUnresolvedBacklog() {
        properties.setEnabled(true);
        properties.setSource("sqs");
        properties.setQueueUrl("https://sqs.us-east-1.amazonaws.com/123/queue");
        properties.setRegion("us-east-1");
        indicator.recordPollSuccess(1);

        indicator.recordUnresolvedBacklog(3);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(true, health.getDetails().get("degraded"));
        assertTrue(String.valueOf(health.getDetails().get("reason")).contains("未解析订阅"));
        assertNotEquals(null, health.getDetails().get("lastSuccessAt"));
    }
}