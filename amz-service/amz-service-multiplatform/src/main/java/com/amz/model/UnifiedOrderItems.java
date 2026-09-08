package com.amz.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一订单明细行序列化工具（B3）。
 * <p>
 * 明细持久化于 {@code amz_unified_order.items_json}（TEXT，见 Flyway V2）；
 * 读时回填 {@link UnifiedOrder#items}。老数据（items_json 为空）按头字段
 * （首行）派生单条明细，保证新老行读取形状一致。
 */
@Slf4j
public final class UnifiedOrderItems {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<UnifiedOrderItem>> LIST_TYPE =
            new TypeReference<List<UnifiedOrderItem>>() {
            };

    private UnifiedOrderItems() {
    }

    /**
     * 明细序列化（写库前调用）。空明细返回 null（列保持 NULL）。
     */
    public static String toJson(List<UnifiedOrderItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("订单明细序列化失败，降级为空", e);
            return null;
        }
    }

    /**
     * 明细反序列化（读库后调用）。非法 JSON 返回空列表，不抛异常阻断列表查询。
     */
    public static List<UnifiedOrderItem> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<UnifiedOrderItem> items = MAPPER.readValue(json, LIST_TYPE);
            return items == null ? new ArrayList<>() : items;
        } catch (Exception e) {
            log.warn("订单明细反序列化失败，降级为空列表", e);
            return new ArrayList<>();
        }
    }

    /**
     * 为读出的订单回填明细：有 JSON 用 JSON；老数据按头字段派生单条；
     * 两者皆无时保持空列表（不臆测）。
     */
    public static void ensureItems(UnifiedOrder order) {
        if (order == null) {
            return;
        }
        List<UnifiedOrderItem> items = fromJson(order.getItemsJson());
        if (items.isEmpty() && order.getSku() != null) {
            UnifiedOrderItem legacy = new UnifiedOrderItem();
            legacy.setSku(order.getSku());
            legacy.setProductName(order.getProductName());
            legacy.setQuantity(order.getQuantity());
            items.add(legacy);
        }
        order.setItems(items);
    }
}
