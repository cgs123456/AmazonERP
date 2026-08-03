package com.amz.client.impl;

import com.amz.client.KeepaClient;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
@Profile("!mock")
public class KeepaRealClient implements KeepaClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String KEEPA_API = "https://api.keepa.com/product";
    private static final Gson GSON = new Gson();

    @Value("${keepa.api-key:}")
    private String apiKey;

    @Override
    public String getPriceHistory(String asin, int domain) {
        return callKeepa(asin, domain, "price");
    }

    @Override
    public String getRankHistory(String asin, int domain) {
        return callKeepa(asin, domain, "rank");
    }

    @Override
    public String getCompetitorAnalysis(String asin, int domain) {
        return callKeepa(asin, domain, "competitor");
    }

    private String callKeepa(String asin, int domain, String type) {
        try {
            if (apiKey == null || apiKey.isBlank()) {
                log.debug("Keepa API key 未配置，跳过调用");
                return null;
            }
            String url = String.format("%s?key=%s&domain=%d&asin=%s&stats=90",
                    KEEPA_API, apiKey, domain, asin);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            log.warn("Keepa API 返回非200: {} asin={} domain={}", resp.statusCode(), asin, domain);
        } catch (Exception e) {
            log.error("Keepa API 调用失败 asin={} domain={}", asin, domain, e);
        }
        return null;
    }
}
