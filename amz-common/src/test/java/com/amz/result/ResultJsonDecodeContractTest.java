package com.amz.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务间调用的响应解码契约。
 * <p>
 * 背景（实测）：product 通过 Feign 调 order 的 {@code /order/fees/lookup}，HTTP 往返与对端应答都正常
 * （OAP 里 Exit/Entry span 成对存在），但调用侧解码失败，被 fallback 静默降级成硬编码估算值，
 * 日志只剩一句 {@code Type definition error: [simple type, class com.amz.result.Result]}。
 * 全仓 20 个 @FeignClient 接口里 38 个方法返回 {@code Result<...>}，全部走同一条解码路径。
 * <p>
 * 既有的 {@link ApiErrorSerializationTest} 只断言写方向（{@code writeValueAsString}），
 * 写方向不需要 creator，所以序列化契约全绿而解码一直是坏的——这里补的正是读方向。
 * 使用 {@link Jackson2ObjectMapperBuilder} 构造 mapper，与 Boot 注入给
 * {@code MappingJackson2HttpMessageConverter}（Feign 的 SpringDecoder 走的就是它）的实例一致。
 */
@DisplayName("服务间响应解码契约（Result 必须能被 Jackson 读回来）")
class ResultJsonDecodeContractTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("普通成功体能解码成 Result<Map>")
    void decodesPlainSuccessEnvelope() throws Exception {
        String json = "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"fulfillmentFee\":3.22,\"storageFee\":0.61}}";

        Result<Map<String, Object>> result = objectMapper.readValue(
                json, new TypeReference<Result<Map<String, Object>>>() { });

        assertNotNull(result, "Result 解码为 null");
        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMessage());
        assertEquals(3.22, ((Number) result.getData().get("fulfillmentFee")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("错误体能解码成 Result<Map>（fallback 之前必须先看得到 code）")
    void decodesFailureEnvelope() throws Exception {
        String json = "{\"code\":400,\"message\":\"参数非法\",\"data\":null,\"error\":"
                + "{\"code\":\"SPAPI_CALL_FAILED\",\"platformStatus\":429,\"requestId\":\"req-1\"}}";

        Result<Map<String, Object>> result = objectMapper.readValue(
                json, new TypeReference<Result<Map<String, Object>>>() { });

        assertEquals(400, result.getCode());
        assertNotNull(result.getError(), "error 字段应被还原，否则调用方无法区分本地错误与平台错误");
        assertEquals("SPAPI_CALL_FAILED", result.getError().getCode());
        assertEquals(429, result.getError().getPlatformStatus());
    }

    @Test
    @DisplayName("分页与字段权限元信息按 _page / _hiddenFields 线名解码")
    void decodesWirePrefixedMetadataFields() throws Exception {
        String json = "{\"code\":200,\"message\":\"操作成功\",\"data\":[{\"asin\":\"A1\"}],"
                + "\"_hiddenFields\":[\"costPrice\"],"
                + "\"_page\":{\"hasMore\":true,\"nextCursor\":\"c-9\"}}";

        Result<List<Map<String, Object>>> result = objectMapper.readValue(
                json, new TypeReference<Result<List<Map<String, Object>>>>() { });

        assertEquals(1, result.getData().size());
        assertEquals(List.of("costPrice"), result.getHiddenFields(),
                "_hiddenFields 是前端显示 *** 的唯一依据，线名必须能读回来");
        assertNotNull(result.getPage(), "_page 读不回来则分页信息在调用链上丢失");
        assertTrue(result.getPage().isHasMore(), "实际：" + result.getPage());
        assertEquals("c-9", result.getPage().getNextCursor());
    }

    @Test
    @DisplayName("写出的 JSON 能被读回（同类型往返），防止只修一侧")
    void roundTripsSerializedPayload() throws Exception {
        Result<Map<String, Object>> original = Result.success(Map.of("asin", "A1", "price", 12.5));
        original.setHiddenFields(List.of("costPrice"));

        Result<Map<String, Object>> decoded = objectMapper.readValue(
                objectMapper.writeValueAsString(original),
                new TypeReference<Result<Map<String, Object>>>() { });

        assertInstanceOf(Result.class, decoded);
        assertEquals(original.getCode(), decoded.getCode());
        assertEquals(original.getData(), decoded.getData());
        assertEquals(original.getHiddenFields(), decoded.getHiddenFields(),
                "往返后 _hiddenFields 必须等价，否则字段级权限在跨服务调用后静默失效");
    }
}
