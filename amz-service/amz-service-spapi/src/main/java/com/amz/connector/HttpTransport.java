package com.amz.connector;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * SP-API 出站 HTTP 传输抽象（把「请求怎么构造」与「请求怎么发出去」解耦）。
 * <p>
 * <b>为什么需要它：</b>真实网络行为无法在无网络栈的沙箱/CI 中验证。抽出传输层后，
 * 请求构造契约（方法、URI、请求头、请求体）可以在进程内 record-and-replay 桩上逐条断言，
 * 证据上限为 **E2（契约构造）**，不得据此宣称已完成真实联调（A5）。
 * <p>
 * <b>实现限制（JDK 17 实测）：</b>{@link #send} 是<b>泛型</b>方法，
 * <b>不能用 lambda 实现</b>；请使用方法引用（{@code httpClient::send}）
 * 或匿名内部类。生产装配见 {@code com.amz.config.HttpClientConfig}。
 */
@FunctionalInterface
public interface HttpTransport {

    /**
     * 发送一次 HTTP 请求并读取响应。
     *
     * @param request 已构造好的请求（头与体均由 {@code SpApiRequestFactory} 注入）
     * @param handler 响应体处理器
     * @param <T>     响应体类型
     * @return HTTP 响应
     * @throws IOException          传输层失败（连接失败/超时/读取中断等）
     * @throws InterruptedException 当前线程被中断
     */
    <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException;
}
