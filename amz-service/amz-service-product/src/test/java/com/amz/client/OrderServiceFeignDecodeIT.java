package com.amz.client;

import com.amz.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import feign.Feign;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConverters;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨服务响应的真实往返解码测试。
 * <p>
 * 存在动因（2026-09-30 实测）：{@code Result<T>} 当时没有任何 Jackson creator
 * （两个带参构造器都未标 {@code @JsonCreator}，也无无参构造器），于是响应壳写得出去、读不回来，
 * 20 个 Feign 接口里 38 个返回 {@code Result<...>} 的方法**每次调用都在解码阶段失败**，
 * 被 fallbackFactory 以 {@code log.warn(cause.getMessage())} 静默吞掉。
 * 当时全仓没有任何测试覆盖这条路径：Feign 相关测试只断言注解与路径，服务层测试用 Mockito
 * 直接绕过编解码，"JSON 契约"测试只测序列化写方向。本测试补的正是这条真实往返。
 * <p>
 * 用 JDK 自带 HttpServer 而不是 WireMock：无需新增依赖，离线构建可用。
 * 解码器用生产同款的 {@link SpringDecoder}（Feign 的 Spring 客户端默认走它），
 * 因此这里断言的是真实编解码路径而不是自建 ObjectMapper 的近似。
 */
@DisplayName("Feign 往返：order 的标准响应必须能被 product 解码")
class OrderServiceFeignDecodeIT {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static OrderServiceFeignClient client;

    /**
     * 桩侧费率表。
     * <p>
     * 载荷字段集合与信封字段顺序取自 2026-09-30 对运行中的 order 服务的实采响应
     * （{@code GET /order/fees/lookup?sizeTier=SYN-size-tier-000006&weight=1} → HTTP 200）：
     * {@code {"data":{"storageFeePerMonth":..,"sizeTier":..,"weightG":..,"fulfillmentFee":..,"region":..},
     * "message":"操作成功","code":200,"_hiddenFields":null}}。
     * 之所以要按实采形状来写：本类守的正是"真实响应读不读得回来"，
     * 自己臆造的响应体只能证明我的想象能被解码。
     */
    private static final Map<String, List<Map<String, Object>>> FEE_TABLES = Map.of(
            "standard", List.of(
                    feeRow("standard", 460, 3.21, 0.55),
                    feeRow("standard", 2100, 3.94, 0.87)));

    @BeforeAll
    static void startStubAndClient() throws Exception {
        ObjectMapper json = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/order/fees/lookup", (HttpExchange exchange) -> {
            String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            LAST_REQUEST.set(exchange.getRequestURI().getPath() + "?" + query);
            Map<String, String> params = parseQuery(query);
            int weight = Integer.parseInt(params.getOrDefault("weight", "0"));
            Map<String, Object> hit = FEE_TABLES.getOrDefault(params.get("sizeTier"), List.of()).stream()
                    .filter(row -> ((Number) row.get("weightG")).intValue() >= weight)
                    .min(Comparator.comparingInt(row -> ((Number) row.get("weightG")).intValue()))
                    .orElse(null);
            byte[] bytes = json.writeValueAsString(envelope(hit)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json().build());
        ClientHttpMessageConvertersCustomizer customizer = builder ->
                builder.registerDefaults().withJsonConverter(converter);
        FeignHttpMessageConverters converters = new FeignHttpMessageConverters(
                provider(customizer), emptyProvider());

        client = Feign.builder()
                .contract(new SpringMvcContract())
                .decoder(new SpringDecoder(provider(converters)))
                .target(OrderServiceFeignClient.class, baseUrl);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return List.of(value).iterator();
            }
        };
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<T>() {
            @Override
            public T getObject() {
                throw new java.util.NoSuchElementException();
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return java.util.Collections.emptyIterator();
            }
        };
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("有费率数据时：Result 信封与 data 都要读得回来")
    void decodesRealFeePayload() {
        Result<Map<String, Object>> result = client.lookupFbaFees("standard", 2100);

        assertInstanceOf(Result.class, result,
                "Feign 解码返回 null 说明响应体没被读出来——这正是本轮修掉的故障形状");
        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMessage());
        Map<String, Object> data = result.getData();
        assertNotNull(data, "data 段必须存在，否则调用方会误判为'对端没配费率'并静默降级");
        assertEquals(3.94, ((Number) data.get("fulfillmentFee")).doubleValue(), 1e-9);
        assertEquals(2100, ((Number) data.get("weightG")).intValue());
        assertEquals(0.87, ((Number) data.get("storageFeePerMonth")).doubleValue(), 1e-9);
        assertNull(result.getHiddenFields(),
                "线上响应总带 _hiddenFields（无字段过滤时为 null），必须能读回而不是当成未知字段炸掉");
    }

    @Test
    @DisplayName("对端无费率配置时：data 为 null 也必须解码成功（不能靠异常区分）")
    void decodesNullDataEnvelope() {
        Result<Map<String, Object>> result = client.lookupFbaFees("oversize", 500);

        assertEquals(200, result.getCode());
        assertNull(result.getData(),
                "对端返回 null data 是合法业务态；解码成功才能让它与'调用失败'区分开");
    }

    @Test
    @DisplayName("请求按 @GetMapping 路径与 @RequestParam 参数发出")
    void sendsContractedRequest() {
        LAST_REQUEST.set(null);
        client.lookupFbaFees("standard", 2100);
        String observed = LAST_REQUEST.get();

        assertNotNull(observed, "桩服务没收到任何请求：路径或参数绑定已失效");
        assertEquals("/order/fees/lookup", observed.substring(0, observed.indexOf('?')),
                "Feign 接口声明的路径变了就必须同步对端契约，实际：" + observed);
        assertTrue(observed.contains("sizeTier=standard"), "sizeTier 未随查询参数发出：" + observed);
        assertTrue(observed.contains("weight=2100"), "weight 未随查询参数发出：" + observed);
    }

    /** 与实采响应同构的信封：字段顺序与 {@code _hiddenFields} 都按线上字节保留。 */
    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", data);
        resp.put("message", "操作成功");
        resp.put("code", 200);
        resp.put("_hiddenFields", null);
        return resp;
    }

    private static Map<String, Object> feeRow(String sizeTier, int weightG,
                                              double fulfillmentFee, double storageFeePerMonth) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("storageFeePerMonth", storageFeePerMonth);
        row.put("sizeTier", sizeTier);
        row.put("weightG", weightG);
        row.put("fulfillmentFee", fulfillmentFee);
        row.put("region", "us");
        return row;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                params.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        return params;
    }
}
