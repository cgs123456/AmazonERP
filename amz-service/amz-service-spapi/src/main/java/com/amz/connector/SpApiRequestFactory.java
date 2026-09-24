package com.amz.connector;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.SpApiUserAgent;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * SP-API 出站请求的唯一构造点（P0-35 / P0-38 / P0-48 / P0-50）。
 * <p>
 * <b>为什么集中在这里：</b>出站类此前各自拼头，导致三类缺陷：
 * <ol>
 *   <li>缺官方必填 {@code user-agent}（P0-35）；</li>
 *   <li>无 AK/SK 时仍进签名分支，产出 {@code Credential=null} 垃圾头（P0-38）；</li>
 *   <li>预签名地址（S3 上传/下载）误带 LWA token 或 AWS 签名（P0-50，
 *       等于把凭证交给第三方存储桶）。</li>
 * </ol>
 * 现在两类请求形态各只有一个出口：{@link #spApi}（SP-API 主机）与 {@link #presigned}（预签名 URL）。
 * <p>
 * <b>请求头规则：</b>
 * <ul>
 *   <li>{@code spApi}：{@code host}/{@code x-amz-date} + 有 AK/SK 时的 {@code Authorization}
 *       + {@code user-agent} + {@code x-amz-access-token}；</li>
 *   <li>{@code presigned}：只有 {@code user-agent}（外加调用方给的 {@code Content-Type}），
 *       <b>绝不</b>注入 token 与签名。</li>
 * </ul>
 * <b>签名区域：</b>{@code awsRegion} 必须是 AWS region（{@code us-east-1}/{@code eu-west-1}/{@code us-west-2}），
 * 由 {@link MarketplaceRegistry#resolveAwsRegion(String)} 从分组码解析（P0-48）。
 */
@Component
public class SpApiRequestFactory {

    /** SP-API 主站调用超时。 */
    private static final Duration SP_API_TIMEOUT = Duration.ofSeconds(30);

    private final AwsSigV4Signer signer;
    private final SpApiUserAgent userAgent;

    public SpApiRequestFactory(AwsSigV4Signer signer, SpApiUserAgent userAgent) {
        this.signer = signer;
        this.userAgent = userAgent;
    }

    /** 当前生效的官方必填 user-agent 值。 */
    public String userAgentValue() {
        return userAgent.value();
    }

    /**
     * 构造一次 SP-API 主机请求（JSON 体；GET 时 {@code body} 传 null）。
     *
     * @param method         HTTP 方法（GET/POST）
     * @param endpoint       官方端点（含 https://），来自 {@link MarketplaceRegistry}
     * @param host           请求主机名（由 registry 提供，不含协议）
     * @param awsRegion      AWS region（参与 SigV4 作用域；注意不是分组码 NA/EU/FE）
     * @param path           请求路径，如 /orders/v0/orders
     * @param canonicalQuery 规范查询串（字典序 + URI 编码；无查询传 null）
     * @param body           JSON 请求体（GET 传 null）
     * @param accessToken    LWA access_token（必须非空白，否则 fail-closed）
     * @param accessKey      IAM Access Key ID（可空 → 不签名）
     * @param secretKey      IAM Secret Access Key（可空 → 不签名）
     */
    public HttpRequest spApi(String method, String endpoint, String host, String awsRegion,
                             String path, String canonicalQuery, String body,
                             String accessToken, String accessKey, String secretKey) {
        String canonicalQueryString = canonicalQuery == null ? "" : canonicalQuery;
        String canonicalBody = body == null ? "" : body;
        String httpMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);

        Map<String, String> signedHeaders = signer.sign(
                httpMethod, host, path, canonicalQueryString, canonicalBody,
                accessKey, secretKey, awsRegion);

        String url = endpoint + path + (canonicalQueryString.isEmpty() ? "" : "?" + canonicalQueryString);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(SP_API_TIMEOUT);
        if (body == null) {
            builder.method(httpMethod, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(httpMethod, HttpRequest.BodyPublishers.ofString(canonicalBody, StandardCharsets.UTF_8));
        }
        signedHeaders.forEach(builder::header);
        builder.header("user-agent", userAgent.value());
        builder.header("x-amz-access-token", requireAccessToken(accessToken));
        return builder.build();
    }

    /**
     * 构造一次预签名 URL 请求（S3 文档上传/下载）。
     * <p>
     * 预签名 URL 自带鉴权参数，<b>不得</b>再带 LWA access token 或 AWS 签名
     * （P0-50：否则凭证会泄露给存储桶）。
     *
     * @param method      HTTP 方法（GET/PUT）
     * @param url         预签名 URL（原样使用，禁止改写查询串）
     * @param contentType 请求体内容类型；无请求体传 null
     * @param body        请求体；无请求体传 null
     * @param timeout     超时（下载与上传口径不同，由调用方决定）
     */
    public HttpRequest presigned(String method, String url, String contentType, String body, Duration timeout) {
        String httpMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout == null ? SP_API_TIMEOUT : timeout)
                .header("user-agent", userAgent.value());
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        if (body == null) {
            builder.method(httpMethod, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(httpMethod, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private static String requireAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("SP-API 调用缺少 LWA access_token（拒绝发送无凭证请求）");
        }
        return accessToken;
    }
}