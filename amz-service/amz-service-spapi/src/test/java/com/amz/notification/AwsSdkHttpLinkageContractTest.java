package com.amz.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AWS SDK 同步 HTTP 客户端的<b>链接</b>契约测试（离线，不发任何网络请求）。
 * <p>
 * 为什么必须有这个类：
 * {@code software.amazon.awssdk:sqs} 传递依赖 {@code apache5-client}，后者在构建同步客户端时
 * 会实例化 {@code Apache5HttpClient}，进而引用
 * {@code org.apache.hc.client5.http.ssl.TlsSocketStrategy}（httpclient5 5.4+ 才有）。
 * 而 {@code httpclient5} / {@code httpcore5} 不在 {@code software.amazon.awssdk:bom} 的管理范围内，
 * 会被 {@code spring-boot-dependencies} 降级（实测：Spring Boot 3.3.5 把 5.6.4 降到 5.3.1）。
 * 降级后抛的是 {@code NoClassDefFoundError} —— 它是 {@code Error} 不是 {@code Exception}，
 * 业务代码的 {@code catch (Exception)} 兜不住；又因为它只在<b>构建客户端</b>那一刻才触发，
 * 所有基于 mock 的单元测试都不会碰到它，本地跑 mock 事件源也永远不会发现。
 * 后果是：{@code spapi.notifications.source=sqs} 时
 * {@link SqsNotificationEventSource} 在构造函数里建客户端，服务启动即失败。
 * <p>
 * 因此这里<b>真的去构建一次</b>客户端，把这类「依赖被降级」的问题钉在 CI 里：
 * 只要有人升级 Spring Boot / AWS SDK 再次压低 httpclient5，这个测试会先炸。
 * <p>
 * 构建客户端不需要凭证也不需要网络：凭证是惰性解析的，HTTP 连接池在 close() 时释放。
 * 不覆盖：真实 SQS 调用（见默认禁用的集成测试）。
 */
class AwsSdkHttpLinkageContractTest {

    private static final String QUEUE_URL =
            "https://sqs.us-east-1.amazonaws.com/123456789012/amz-erp-notifications";
    private static final String ROLE_ARN =
            "arn:aws:iam::123456789012:role/amz-erp-notification-consumer";

    @Test
    @DisplayName("apache5-client 引用的 httpclient5 类必须可加载：缺失即启动期 NoClassDefFoundError")
    void apacheHttpClient5ClassIsPresent() {
        assertDoesNotThrow(
                () -> Class.forName("org.apache.hc.client5.http.ssl.TlsSocketStrategy"),
                "org.apache.hc.client5.http.ssl.TlsSocketStrategy 不可加载："
                        + "httpclient5 被降级到了 5.4 以下。请检查根 pom 钉住的 httpclient5.version"
                        + " 是否被 spring-boot-dependencies 覆盖。");
    }

    @Test
    @DisplayName("同步 SqsClient 可离线构建：构建期即实例化 HTTP 客户端，链接错误必须在此暴露")
    void syncSqsClientCanBeBuiltOffline() {
        NotificationProperties properties = new NotificationProperties();
        properties.setSource("sqs");
        properties.setQueueUrl(QUEUE_URL);
        properties.setRegion("us-east-1");

        SqsClient client = assertDoesNotThrow(
                () -> SqsNotificationEventSource.createClient(properties),
                "构建 SqsClient 失败：AWS SDK 同步 HTTP 客户端的依赖被降级，"
                        + "SQS 模式下服务会启动即失败。");

        assertNotNull(client, "SqsClient 不应为 null");
        client.close();
    }

    @Test
    @DisplayName("STS AssumeRole 凭证提供者可离线构建：它内部持有真实 StsClient，同样依赖 HTTP 客户端")
    void stsCredentialsProviderCanBeBuiltOffline() {
        AwsCredentialsProvider provider = assertDoesNotThrow(
                () -> SqsNotificationEventSource.credentialsProvider(ROLE_ARN, "us-east-1"),
                "构建 StsAssumeRoleCredentialsProvider 失败：StsClient 依赖的 HTTP 客户端不可用。");

        assertInstanceOf(StsAssumeRoleCredentialsProvider.class, provider);
    }
}
