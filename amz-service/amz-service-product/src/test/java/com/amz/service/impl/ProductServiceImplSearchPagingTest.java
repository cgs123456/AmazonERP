package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.ProductMapper;
import com.amz.model.pojo.Product;
import com.amz.result.PageRequest;
import com.amz.result.Result;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品搜索分页契约测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 回归 P1-01：searchProducts 原先两条分支都硬编码 {@code LIMIT 20}，
 * HTTP 200 且响应结构与「确实只有 20 条」完全一致，调用方无从判断被截断。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("商品搜索分页契约")
class ProductServiceImplSearchPagingTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private ProductServiceImpl productService;

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    /**
     * 纯 Mockito 测试没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时会抛
     * “can not find lambda cache for this entity”，手动注册表元信息后再断言 SQL 片段。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Product.class);
    }

    @Test
    @DisplayName("searchProducts：命中超过一页时 truncated=true、message 标记截断，探测行不泄漏")
    void searchProductsTruncated() {
        UserContext.setShopId(1L);
        when(productMapper.selectList(any()))
                .thenReturn(List.of(product(3), product(2)));

        Result<List<Product>> res = productService.searchProducts("case", PageRequest.first(1));

        assertEquals(1, res.getData().size(), "第 size+1 行只用于判定 hasMore");
        assertNotNull(res.getPage());
        assertTrue(res.getPage().isTruncated());
        assertEquals(PageRequest.encodeCursor("3"), res.getPage().getNextCursor(),
                "游标必须是本页最后一行的 id");
        assertEquals(Result.MSG_TRUNCATED, res.getMessage(),
                "截断响应不能继续复用「操作成功」");
    }

    @Test
    @DisplayName("searchProducts：未取满一页时 truncated=false，且不输出 nextCursor")
    void searchProductsNotTruncated() {
        UserContext.setShopId(1L);
        when(productMapper.selectList(any())).thenReturn(List.of(product(1)));

        Result<List<Product>> res = productService.searchProducts(null, PageRequest.first(50));

        assertFalse(res.getPage().isTruncated());
        assertNull(res.getPage().getNextCursor());
        assertEquals("操作成功", res.getMessage());
    }

    @Test
    @DisplayName("searchProducts：携带 cursor 时按 id 下界过滤，且始终带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void searchProductsWithCursorUsesKeyset() {
        UserContext.setShopId(1L);
        when(productMapper.selectList(any())).thenReturn(List.of(product(5)));
        PageRequest req = PageRequest.of(10, PageRequest.encodeCursor("900"));

        productService.searchProducts("case", req);

        ArgumentCaptor<LambdaQueryWrapper<Product>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(productMapper).selectList(captor.capture());
        LambdaQueryWrapper<Product> wrapper = captor.getValue();
        // 必须先触发 getCustomSqlSegment()：MyBatis-Plus 参数表惰性填充
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "商品搜索必须带显式 LIMIT");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900),
                "keyset 分页必须把游标值作为 id 下界，实际参数表=" + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("searchProducts：未选择店铺时诚实失败，不返回跨店数据")
    void searchProductsWithoutShop() {
        UserContext.clear();

        Result<List<Product>> res = productService.searchProducts("case", PageRequest.first(10));

        assertEquals(400, res.getCode());
        assertEquals("请先选择店铺", res.getMessage());
        assertNull(res.getData());
    }

    private static Product product(Integer id) {
        Product p = new Product();
        p.setId(id);
        p.setShopId(1);
        p.setName("P-" + id);
        return p;
    }
}
