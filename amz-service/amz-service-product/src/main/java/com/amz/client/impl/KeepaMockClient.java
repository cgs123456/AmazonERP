package com.amz.client.impl;

import com.amz.client.KeepaClient;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@Profile("mock")
public class KeepaMockClient implements KeepaClient {

    private static final Gson GSON = new Gson();

    @Override
    public String getPriceHistory(String asin, int domain) {
        return mockResponse("priceHistory", asin);
    }

    @Override
    public String getRankHistory(String asin, int domain) {
        return mockResponse("rankHistory", asin);
    }

    @Override
    public String getCompetitorAnalysis(String asin, int domain) {
        return mockResponse("competitorAnalysis", asin);
    }

    private String mockResponse(String type, String asin) {
        double base = 10 + ThreadLocalRandom.current().nextDouble() * 40;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("asin", asin);
        data.put("type", type);
        if ("priceHistory".equals(type)) {
            data.put("currentPrice", String.format("%.2f", base));
            data.put("avgPrice30d", String.format("%.2f", base * (0.9 + ThreadLocalRandom.current().nextDouble() * 0.2)));
            data.put("lowestPrice", String.format("%.2f", base * 0.8));
        } else if ("rankHistory".equals(type)) {
            data.put("currentRank", ThreadLocalRandom.current().nextInt(1, 10000));
            data.put("avgRank30d", ThreadLocalRandom.current().nextInt(500, 15000));
        } else {
            data.put("competitorCount", ThreadLocalRandom.current().nextInt(3, 20));
            data.put("buyBoxWinner", ThreadLocalRandom.current().nextBoolean() ? "AMAZON" : "OTHER");
        }
        return GSON.toJson(data);
    }
}
