package com.amz.testsupport;

import com.amz.connector.HttpTransport;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Function;

/**
 * 进程内 record-and-replay HTTP 传输：记录每个出站 {@link HttpRequest} 并按 responder 返回预置响应。
 * <p>
 * <b>零 socket</b>——因此在不具备可用网络栈、连 JDK {@code HttpClient} 都无法构造的环境
 * （本仓库 CI 沙箱即如此，见计划「未验证与风险」第 18 条）中仍可稳定运行。
 * <p>
 * 覆盖范围是<b>请求构造契约</b>（方法、URI、头、体）与客户端分支逻辑；
 * <b>不</b>覆盖 JDK {@code HttpClient} 的真实网络行为，证据上限 **E2**（spec §1.9.1），
 * 不得据此宣称 A1/A5 通过。
 */
public final class RecordingHttpTransport implements HttpTransport {

    private final Function<HttpRequest, Reply> responder;
    private final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

    private RecordingHttpTransport(Function<HttpRequest, Reply> responder) {
        this.responder = responder;
    }

    /** 按请求定制响应。 */
    public static RecordingHttpTransport of(Function<HttpRequest, Reply> responder) {
        return new RecordingHttpTransport(responder);
    }

    /** 所有请求统一返回 200 + 同一 JSON 体。 */
    public static RecordingHttpTransport json(String json) {
        return new RecordingHttpTransport(request -> new Reply(200, json));
    }

    /** 传输层恒定失败：用于验证客户端**不吞** IOException（记录仍然生效）。 */
    public static RecordingHttpTransport failing(IOException failure) {
        return new RecordingHttpTransport(request -> {
            throw new UncheckedIOException(failure);
        });
    }

    /** 已记录的请求快照（按发生顺序）。 */
    public List<HttpRequest> requests() {
        return new ArrayList<>(requests);
    }

    /** 最近一次请求；无请求时抛 {@link IllegalStateException}。 */
    public HttpRequest lastRequest() {
        List<HttpRequest> snapshot = requests();
        if (snapshot.isEmpty()) {
            throw new IllegalStateException("no request recorded");
        }
        return snapshot.get(snapshot.size() - 1);
    }

    public int requestCount() {
        return requests.size();
    }

    /**
     * 读取请求体字节：订阅 {@link HttpRequest#bodyPublisher()}（纯进程内，无网络）。
     * 无请求体（GET 等）返回空数组。
     */
    public static byte[] bodyBytes(HttpRequest request) {
        Optional<HttpRequest.BodyPublisher> publisher = request.bodyPublisher();
        if (publisher.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.get().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.join();
        return out.toByteArray();
    }

    /** 读取请求体文本（UTF-8）。 */
    public static String bodyText(HttpRequest request) {
        return new String(bodyBytes(request), StandardCharsets.UTF_8);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        requests.add(request);
        Reply reply;
        try {
            reply = responder.apply(request);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        HttpResponse.ResponseInfo info = new Info(reply.status(),
                HttpHeaders.of(reply.headers(), (name, value) -> true), HttpClient.Version.HTTP_1_1);
        HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                // 本类主动推送数据，不依赖订阅方拉取
            }

            @Override
            public void cancel() {
                // no-op
            }
        });
        byte[] bytes = reply.bodyAsBytes();
        if (bytes.length > 0) {
            subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
        }
        subscriber.onComplete();
        T body = subscriber.getBody().toCompletableFuture().join();
        return new FakeResponse<>(reply.status(), reply.headers(), request, body);
    }

    /**
     * 预置响应：状态码 + 文本体 + 响应头。
     * <p>
     * 响应头是必需能力（而非装饰）：{@code x-amzn-RateLimit-Limit} 回填本地限流窗口
     * （P0-51 桩回放）只有读到真实响应头才可断言。
     */
    public record Reply(int status, String body, Map<String, List<String>> headers, byte[] rawBody) {

        /** 兼容无响应头场景（等价于空头集）。 */
        public Reply(int status, String body) {
            this(status, body, Map.of(), null);
        }

        /** 兼容既有三参调用点（等价于无二进制体）。 */
        public Reply(int status, String body, Map<String, List<String>> headers) {
            this(status, body, headers, null);
        }

        /** 单值响应头构造器（如 {@code x-amzn-RateLimit-Limit: 0.1}）。 */
        public static Reply withHeader(int status, String body, String name, String value) {
            return new Reply(status, body, Map.of(name, List.of(value)), null);
        }

        /**
         * 原始字节响应体：GZIP 等二进制文档（结算原表）必须走这里。
         * 文本体会按 UTF-8 编码；二进制体不会再被二次编码（否则 GZIP 字节会被破坏）。
         */
        public static Reply ofBytes(int status, byte[] rawBody) {
            return new Reply(status, null, Map.of(), rawBody);
        }

        /** 响应体字节：优先 rawBody，其次 UTF-8 编码的文本体。 */
        public byte[] bodyAsBytes() {
            if (rawBody != null) {
                return rawBody;
            }
            return body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        }
    }

    private record Info(int statusCode, HttpHeaders headers, HttpClient.Version version)
            implements HttpResponse.ResponseInfo {
    }

    private static final class FakeResponse<T> implements HttpResponse<T> {

        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final HttpRequest request;
        private final T body;

        FakeResponse(int statusCode, Map<String, List<String>> headers, HttpRequest request, T body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.request = request;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(headers, (name, value) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
