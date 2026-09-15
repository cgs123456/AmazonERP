package com.amz.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 承运商状态映射器单元测试（纯单元，无 Spring 上下文）。
 * <p>
 * 重点覆盖两类易回归点：
 * <ol>
 *   <li><b>否定式误判</b>：{@code UNDELIVERED} 含子串 {@code DELIVERED}，
 *       若关键词兜底顺序错误，「投递失败」会被记成「已签收」——异常件从看板上消失、
 *       时效统计反而变好，属于被数据掩盖的错误，必须由测试守住；</li>
 *   <li><b>永不丢弃</b>：无法识别时必须归入 IN_TRANSIT 并告警，而不是返回 null 或抛异常，
 *       否则轨迹点会在落库前就丢掉，时效统计直接失真。</li>
 * </ol>
 */
@DisplayName("承运商状态映射器单元测试")
class TrackingStatusMapperTest {

    private final TrackingStatusMapper mapper = new TrackingStatusMapper();

    @Test
    @DisplayName("已是内部状态码 → 直接采用，不做二次映射")
    void testInternalStatusPassThrough() {
        assertEquals("IN_TRANSIT", mapper.toInternal(null, "IN_TRANSIT"));
        assertEquals("DELIVERED", mapper.toInternal(null, "delivered"));
        assertEquals("OUT_FOR_DELIVERY", mapper.toInternal("OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY"));
    }

    @Test
    @DisplayName("中文状态文本 → 映射到内部状态")
    void testChineseText() {
        assertEquals("CREATED", mapper.toInternal("已创建", null));
        assertEquals("DEPARTED", mapper.toInternal("已开船", null));
        assertEquals("IN_TRANSIT", mapper.toInternal("运输中", null));
        assertEquals("CUSTOMS_CLEARANCE", mapper.toInternal("清关中", null));
        assertEquals("ARRIVED", mapper.toInternal("已到港", null));
        assertEquals("OUT_FOR_DELIVERY", mapper.toInternal("派送中", null));
        assertEquals("DELIVERED", mapper.toInternal("已签收", null));
        assertEquals("EXCEPTION", mapper.toInternal("查验", null));
    }

    @Test
    @DisplayName("承运商英文文本 → 映射到内部状态")
    void testEnglishText() {
        assertEquals("DEPARTED", mapper.toInternal("PICKED UP", null));
        assertEquals("IN_TRANSIT", mapper.toInternal("In Transit", null));
        assertEquals("CUSTOMS_CLEARANCE", mapper.toInternal("Cleared Customs", null));
        assertEquals("OUT_FOR_DELIVERY", mapper.toInternal("Out for delivery", null));
        assertEquals("DELIVERED", mapper.toInternal("Delivered", null));
    }

    @Test
    @DisplayName("17track stage 枚举 → 映射到内部状态")
    void testSeventeenTrackStages() {
        assertEquals("CREATED", mapper.toInternal("InfoReceived", null));
        assertEquals("IN_TRANSIT", mapper.toInternal("InTransit", null));
        assertEquals("DEPARTED", mapper.toInternal("PickUp", null));
        assertEquals("OUT_FOR_DELIVERY", mapper.toInternal("OutForDelivery", null));
        assertEquals("ARRIVED", mapper.toInternal("AvailableForPickup", null));
        assertEquals("DELIVERED", mapper.toInternal("Delivered", null));
        assertEquals("EXCEPTION", mapper.toInternal("Expired", null));
    }

    @Test
    @DisplayName("回归：UNDELIVERED 必须映射为异常，不得被判成已签收")
    void testUndeliveredIsNotDelivered() {
        // 该值含子串 DELIVERED，是关键词兜底最容易踩的坑
        assertEquals("EXCEPTION", mapper.toInternal("Undelivered", null));
        assertEquals("EXCEPTION", mapper.toInternal("UNDELIVERED", null));
        // 字典与关键词兜底都未命中时应走否定式前置判定
        assertEquals("EXCEPTION", mapper.toInternal("Undelivered - final attempt", null));
        assertEquals("EXCEPTION", mapper.toInternal("DeliveryFailure", null));
        assertEquals("EXCEPTION", mapper.toInternal("投递失败", null));
    }

    @Test
    @DisplayName("关键词兜底：带修饰词的长句仍可识别")
    void testKeywordFallback() {
        assertEquals("CUSTOMS_CLEARANCE", mapper.toInternal("清关完成（已放行）", null));
        assertEquals("DELIVERED", mapper.toInternal("已送达 FBA 仓库", null));
        assertEquals("ARRIVED", mapper.toInternal("货船已抵达洛杉矶港", null));
    }

    @Test
    @DisplayName("外部配置优先于内置字典，且非法值不被采纳")
    void testExternalConfigPrecedence() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("SPECIAL-STATUS", "DELIVERED");
        // 覆盖内置值的场景
        mapping.put("已开船", "IN_TRANSIT");
        mapper.setStatusMapping(mapping);

        assertEquals("DELIVERED", mapper.toInternal("SPECIAL-STATUS", null));
        assertEquals("IN_TRANSIT", mapper.toInternal("已开船", null),
                "外部配置应覆盖内置字典");

        // 配置写错（值不是合法内部状态）时不得污染入库数据，应回落到内置字典
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("已开船", "NOT_A_STATUS");
        mapper.setStatusMapping(bad);
        assertEquals("DEPARTED", mapper.toInternal("已开船", null),
                "非法配置值应被忽略并回落到内置字典");
    }

    @Test
    @DisplayName("无法识别 → 归入 IN_TRANSIT 兜底，永不丢弃")
    void testUnknownNeverDropped() {
        assertEquals("IN_TRANSIT", mapper.toInternal("ZZZZ-UNKNOWN-9999", null));
        assertEquals("IN_TRANSIT", mapper.toInternal(null, null));
        assertEquals("IN_TRANSIT", mapper.toInternal("   ", null));
    }
}
