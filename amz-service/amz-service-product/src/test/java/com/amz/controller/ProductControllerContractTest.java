package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.model.ListingCopyTask;
import com.amz.result.Result;
import com.amz.service.ListingCopyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ProductController Listing 复制接口契约测试")
class ProductControllerContractTest {

    private ProductController controller;
    private ListingCopyService listingCopyService;

    @BeforeEach
    void setUp() {
        controller = new ProductController();
        listingCopyService = mock(ListingCopyService.class);
        ReflectionTestUtils.setField(controller, "listingCopyService", listingCopyService);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("listing/copy 必须声明 OPERATOR/ADMIN 角色守卫")
    void copyListingMustRequireOperatorRole() throws Exception {
        RequireRole role = ProductController.class
                .getMethod("copyListing", Map.class)
                .getAnnotation(RequireRole.class);

        assertNotNull(role, "写接口不能缺少 @RequireRole");
        assertArrayEquals(new String[]{"OPERATOR", "ADMIN"}, role.value());
    }

    @Test
    @DisplayName("请求体 shopId 越权时必须使用严格店铺边界并拒绝")
    void copyListingMustUseStrictShopScope() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(2L));

        Result<Map<String, Object>> result = controller.copyListing(requestWithSku("SKU-1"));

        assertEquals(400, result.getCode());
        verifyNoInteractions(listingCopyService);
    }

    @Test
    @DisplayName("ASIN 参数必须走 ASIN 解析入口，不能降级为 SKU 查询")
    void copyListingMustPassAsinToDedicatedServiceMethod() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));

        ListingCopyTask task = new ListingCopyTask();
        task.setId(99L);
        task.setShopId(1L);
        task.setSku("SKU-REAL");
        task.setProductType("LUGGAGE");
        task.setSourceMarketplaceId("ATVPDKIKX0DER");
        task.setTargetMarketplaceId("A1PA6795UKMFR9");
        task.setTargetLanguage("de");
        task.setStatus("PENDING");
        when(listingCopyService.createCopyTaskByAsin(eq(1L), eq("B08X4"), eq("ATVPDKIKX0DER"),
                eq("A1PA6795UKMFR9"), eq("de"), any(BigDecimal.class))).thenReturn(task);

        Map<String, Object> request = new HashMap<>();
        request.put("shopId", 1L);
        request.put("asin", "B08X4");
        request.put("sourceMarketplaceId", "ATVPDKIKX0DER");
        request.put("targetMarketplaceId", "A1PA6795UKMFR9");
        request.put("targetLanguage", "de");

        Result<Map<String, Object>> result = controller.copyListing(request);

        assertEquals(200, result.getCode());
        assertEquals("SKU-REAL", result.getData().get("sku"));
        verify(listingCopyService).createCopyTaskByAsin(eq(1L), eq("B08X4"), eq("ATVPDKIKX0DER"),
                eq("A1PA6795UKMFR9"), eq("de"), any(BigDecimal.class));
    }

    @Test
    @DisplayName("sku 与 asin 同时提供时必须拒绝歧义请求")
    void copyListingMustRejectAmbiguousSourceIdentifier() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));

        Map<String, Object> request = requestWithSku("SKU-1");
        request.put("asin", "B08X4");

        Result<Map<String, Object>> result = controller.copyListing(request);

        assertEquals(400, result.getCode());
        verifyNoInteractions(listingCopyService);
    }

    private Map<String, Object> requestWithSku(String sku) {
        Map<String, Object> request = new HashMap<>();
        request.put("shopId", 1L);
        request.put("sku", sku);
        request.put("sourceMarketplaceId", "ATVPDKIKX0DER");
        request.put("targetMarketplaceId", "A1PA6795UKMFR9");
        request.put("targetLanguage", "de");
        return request;
    }
}
