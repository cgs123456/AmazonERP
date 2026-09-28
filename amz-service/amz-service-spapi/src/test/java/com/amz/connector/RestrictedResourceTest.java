package com.amz.connector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RDT 受限资源规范化与边界校验")
class RestrictedResourceTest {

    @Test
    @DisplayName("合法 method/path 被规范化，dataElements 去重保序")
    void normalizesValidResource() {
        RestrictedResource resource = new RestrictedResource(
                " get ", "/orders/v0/orders/123-1234567-1234567/orderItems",
                List.of(" buyerInfo ", "buyerInfo", "shippingAddress"));

        assertEquals("GET", resource.method());
        assertEquals("/orders/v0/orders/123-1234567-1234567/orderItems", resource.path());
        assertEquals(List.of("buyerInfo", "shippingAddress"), resource.dataElements());
    }

    @Test
    @DisplayName("method 只接受 Tokens API 官方枚举：GET/PUT/POST/DELETE")
    void rejectsUnsupportedMethod() {
        for (String method : List.of("PATCH", "HEAD", "OPTIONS", "", " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RestrictedResource(method, "/orders/v0/orders", List.of()),
                    "method should be rejected: " + method);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedResource(null, "/orders/v0/orders", List.of()));
    }

    @Test
    @DisplayName("path 必须是绝对路径，且拒绝查询串、片段和控制字符")
    void rejectsUnsafePath() {
        for (String path : List.of("", " ", "orders/v0/orders", "/orders/v0/orders?x=1",
                "/orders/v0/orders#frag", "/orders/v0/orders\nX", "/orders/v0/orders\u0000")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RestrictedResource("GET", path, List.of()),
                    "path should be rejected: " + path);
        }
    }

    @Test
    @DisplayName("dataElements 不得包含空白项，异常文本不回显完整 path")
    void rejectsBlankDataElementWithoutLeakingPath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new RestrictedResource("GET", "/orders/v0/orders/123-1234567-1234567",
                        List.of("buyerInfo", " ")));
        assertFalse(e.getMessage().contains("123-1234567-1234567"), e.getMessage());
        assertTrue(e.getMessage().contains("dataElements"), e.getMessage());
    }

    @Test
    @DisplayName("资源集合必须非空、最多 50 项、不得重复")
    void validatesResourceSet() {
        assertThrows(IllegalArgumentException.class,
                () -> RestrictedResource.canonicalKey(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RestrictedResource.canonicalKey(null));

        List<RestrictedResource> duplicate = List.of(
                new RestrictedResource("GET", "/orders/v0/orders", List.of("buyerInfo")),
                new RestrictedResource("get", "/orders/v0/orders", List.of("buyerInfo")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedResource.canonicalKey(duplicate));

        List<RestrictedResource> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(new RestrictedResource("GET", "/orders/v0/orders/" + i, List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> RestrictedResource.canonicalKey(tooMany));
    }

    @Test
    @DisplayName("同一资源集合的顺序不同仍生成相同 canonical key")
    void canonicalKeyIsStableAcrossOrdering() {
        RestrictedResource orders = new RestrictedResource("GET", "/orders/v0/orders",
                List.of("shippingAddress", "buyerInfo"));
        RestrictedResource order = new RestrictedResource("GET", "/orders/v0/orders/123-1234567-1234567",
                List.of("buyerInfo"));

        String first = RestrictedResource.canonicalKey(List.of(orders, order));
        String second = RestrictedResource.canonicalKey(List.of(order, orders));

        assertEquals(first, second);
        assertNotEquals(first,
                RestrictedResource.canonicalKey(List.of(order)));
        assertFalse(orders.toString().contains("Atza|"), orders.toString());
    }
}