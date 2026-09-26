package com.amz.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SP-API 通知入站启动校验（fail-closed）测试。
 * <p>
 * 这些断言保护的是"失效必须可见"：配置错误必须在启动期抛异常，
 * 不允许在运行时把"没收到通知"伪装成"今天没有通知"。
 */
class NotificationStartupCheckTest {

    private static NotificationProperties newSqsProps() {
        NotificationProperties props = new NotificationProperties();
        props.setSource(NotificationStartupCheck.SOURCE_SQS);
        props.setQueueUrl("https://sqs.us-east-1.amazonaws.com/123456789012/amz-spapi-notifications");
        props.setRegion("us-east-1");
        return props;
    }

    @Test
    void prodWithMockSourceIsRejected() {
        NotificationProperties props = new NotificationProperties();
        props.setSource("mock");
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void sqsWithoutQueueUrlIsRejected() {
        NotificationProperties props = new NotificationProperties();
        props.setSource("sqs");
        props.setRegion("us-east-1");
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void sqsWithoutRegionIsRejected() {
        NotificationProperties props = new NotificationProperties();
        props.setSource("sqs");
        props.setQueueUrl("https://sqs.us-east-1.amazonaws.com/123456789012/amz-spapi-notifications");
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void sqsFifoQueueIsRejected() {
        NotificationProperties props = newSqsProps();
        props.setQueueUrl("https://sqs.us-east-1.amazonaws.com/123456789012/amz-spapi-notifications.fifo");
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void shopStaticAwsCredentialsAreRejected() {
        NotificationProperties props = newSqsProps();
        props.setUseShopStaticCredentials(true);
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void leaseShorterThanVisibilityIsRejected() {
        NotificationProperties props = newSqsProps();
        props.setLeaseTimeoutSeconds(30);
        props.setVisibilityTimeoutSeconds(60);
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void visibilityBelowMinimumIsRejected() {
        NotificationProperties props = newSqsProps();
        props.setVisibilityTimeoutSeconds(10);
        props.setLeaseTimeoutSeconds(600);
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void visibilityAboveSqsMaxIsRejected() {
        NotificationProperties props = newSqsProps();
        props.setVisibilityTimeoutSeconds(43201);
        props.setLeaseTimeoutSeconds(43201);
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void unknownSourceIsRejected() {
        NotificationProperties props = new NotificationProperties();
        props.setSource("kafka");
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void maxMessagesOutOfRangeIsRejected() {
        NotificationProperties props = newSqsProps();
        props.setMaxMessages(0);
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void nullSourceIsRejected() {
        NotificationProperties props = new NotificationProperties();
        props.setSource("");
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", props));
    }

    @Test
    void validSqsOnProdPasses() {
        assertDoesNotThrow(() -> NotificationStartupCheck.validate("prod", newSqsProps()));
    }

    @Test
    void mockOnNonProdPasses() {
        NotificationProperties props = new NotificationProperties();
        props.setSource("mock");
        assertDoesNotThrow(() -> NotificationStartupCheck.validate("dev", props));
    }

    @Test
    void nullPropertiesAreRejected() {
        assertThrows(IllegalStateException.class,
                () -> NotificationStartupCheck.validate("prod", null));
    }
}