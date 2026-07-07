package com.amz.util;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * http自定义请求
 */
public class HttpUtil {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * get请求
     * @param url
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    public static String get(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return response.body();
    }

    /**
     * post请求
     * @param url
     * @param data
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    public static Object post(String url, Object data) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String json = new Gson().toJson(data);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return response.body();
    }

    /**
     * post请求（带自定义请求头）
     * @param url
     * @param data
     * @param userId
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    public static String postWithUserId(String url, Object data, Integer userId) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String json = new Gson().toJson(data);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("userId", String.valueOf(userId))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return response.body();
    }
}
