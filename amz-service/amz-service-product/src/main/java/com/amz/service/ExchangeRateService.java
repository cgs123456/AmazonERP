package com.amz.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 汇率服务：调用 ExchangeRate-API (open.er-api.com) 获取实时汇率。
 * 失败时降级返回 1（同币种原值），保证业务流程不中断。
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private static final String API_BASE = "https://open.er-api.com/v6/latest/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson = new Gson();

    /**
     * 获取 fromCurrency -> toCurrency 的汇率。
     *
     * @param fromCurrency 源币种代码（如 USD）
     * @param toCurrency   目标币种代码（如 EUR）
     * @return 汇率；同币种或调用失败返回 BigDecimal.ONE
     */
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null) {
            return BigDecimal.ONE;
        }
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return BigDecimal.ONE;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + fromCurrency.toUpperCase()))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.warn("ExchangeRate-API returned status {} for {}->{}",
                        response.statusCode(), fromCurrency, toCurrency);
                return BigDecimal.ONE;
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject rates = body.has("rates") && body.get("rates").isJsonObject()
                    ? body.getAsJsonObject("rates") : null;
            if (rates == null || !rates.has(toCurrency.toUpperCase())) {
                log.warn("ExchangeRate-API missing rate for {}->{}", fromCurrency, toCurrency);
                return BigDecimal.ONE;
            }
            BigDecimal rate = rates.get(toCurrency.toUpperCase()).getAsBigDecimal();
            log.info("ExchangeRate {}->{} = {}", fromCurrency, toCurrency, rate);
            return rate;
        } catch (Exception e) {
            log.warn("ExchangeRate lookup failed {}->{}: {}", fromCurrency, toCurrency, e.getMessage());
            return BigDecimal.ONE;
        }
    }
}
