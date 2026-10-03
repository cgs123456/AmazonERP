package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.ProductMapper;
import com.amz.model.dto.ProductDto;
import com.amz.model.pojo.Product;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 旧商品接口（ProductMapper 通路）的漂移收口契约。
 * <p>
 * 动因：{@code Product} 实体映射 name/type/image/sales/stock/user_id，而
 * {@code amz_product} 的 Flyway 新 schema 只有 shop_id/sku/asin/title 等列，
 * V2 也只补了 product_type——没有任何迁移补上旧列。因此这 5 个接口今天一调就是
 * MySQL 1054 → HTTP 500，堆栈里才是可读信息。要求在入口即诚实拒绝并指名替代接口。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("旧商品接口漂移收口")
class ProductLegacyDriftFenceTest {

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private ProductServiceImpl productService;

    @BeforeEach
    @AfterEach
    void resetContext() {
        UserContext.clear();
    }

    /** 五个 HTTP 入口：路径见 ProductController。 */
    private Map<String, Supplier<Result<?>>> legacyEndpoints() {
        ProductDto dto = new ProductDto();
        dto.setId(7);
        dto.setShopId(3);
        dto.setName("旧口径商品名");
        Map<String, Supplier<Result<?>>> calls = new LinkedHashMap<>();
        calls.put("GET /product/getProductList", productService::getProductList);
        calls.put("GET /product/getProduct/{id}", () -> productService.getProduct(7));
        calls.put("GET /product/getProductsByShop/{id}", () -> productService.getProductByShop(7));
        calls.put("POST /product/postProduct", () -> productService.postProduct(dto));
        calls.put("PUT /product/updateProduct", () -> productService.updateProduct(dto));
        return calls;
    }

    @Test
    @DisplayName("五个旧入口一律 400 诚实拒绝，且不下探 ProductMapper")
    void allLegacyEndpointsRefuse() {
        Map<String, Supplier<Result<?>>> calls = legacyEndpoints();
        assertEquals(5, calls.size(), "收口清单必须覆盖 ProductController 的 5 个旧映射");

        // 逐个入口判定并汇总，否则第一个失败就中断，看不出还有哪几个入口漏了守卫
        List<String> offending = new java.util.ArrayList<>();
        for (Map.Entry<String, Supplier<Result<?>>> e : calls.entrySet()) {
            Result<?> res = e.getValue().get();
            String why = e.getKey() + " → code=" + res.getCode() + " data=" + res.getData()
                    + " msg=" + res.getMessage();
            if (res.getCode() != 400 || res.getData() != null
                    || res.getMessage() == null || !res.getMessage().contains("amz_product")) {
                offending.add(why);
            }
        }
        assertTrue(offending.isEmpty(),
                "以下旧入口没有按漂移契约拒绝（expected 400 + data=null + 理由含 amz_product）：\n"
                        + String.join("\n", offending));
        verifyNoInteractions(productMapper);
    }

    @Test
    @DisplayName("拒绝文案指名可用的替代接口，调用方知道往哪走")
    void refusalNamesTheWorkingReplacement() {
        Result<?> res = productService.getProductList();

        assertTrue(res.getMessage().contains("/product/master"),
                "必须指向新 schema 的主数据接口，实际=" + res.getMessage());
        assertTrue(res.getMessage().contains("迁移"),
                "必须说明解法是补齐列的迁移 SQL，实际=" + res.getMessage());
    }

    @Test
    @DisplayName("未选店铺时也是漂移拒绝优先：挡在归属校验前，不因上下文不同而漏到 SQL 层")
    void refusalPrecedesShopContextChecks() {
        UserContext.clear();

        Result<List<Product>> res = productService.getProductList();

        assertEquals(400, res.getCode());
        assertTrue(res.getMessage().contains("amz_product"),
                "不能返回「请先选择店铺」这种可绕过的前置提示，实际=" + res.getMessage());
        verifyNoInteractions(productMapper);
    }
}
