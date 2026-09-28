package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.SupplierMapper;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.Supplier;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("供应商列表游标分页与关键词契约测试")
class SupplierServiceImplPagingTest {

    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private SupplierProductMapper supplierProductMapper;
    @Mock
    private PurchaseOrderMapper purchaseOrderMapper;
    @Mock
    private InventoryBatchMapper inventoryBatchMapper;

    @InjectMocks
    private SupplierServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Supplier.class);
    }

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(9);
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    @DisplayName("供应商列表：探测行不返回，游标保留评分与 id 的稳定排序键")
    void truncatedPageDropsProbeRowAndUsesCompositeCursor() {
        when(supplierMapper.selectList(any())).thenReturn(List.of(
                supplier(40L, "4.5"),
                supplier(39L, "4.4"),
                supplier(38L, "4.3")));

        PageResult<Supplier> page = service.listSuppliers(
                1L, null, null, PageRequest.first(2));

        assertEquals(List.of(40L, 39L), page.items().stream().map(Supplier::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor("4.4|39"), page.nextCursor());
    }

    @Test
    @DisplayName("供应商列表：keyword 必须生成模糊匹配条件，status 仍精确过滤，并带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void keywordUsesLikeAndCursorUsesCompositeKeyset() {
        when(supplierMapper.selectList(any())).thenReturn(List.of(supplier(5L, "4.0")));
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor("4.5|900"));

        service.listSuppliers(1L, "ACTIVE", "acme", request);

        ArgumentCaptor<LambdaQueryWrapper<Supplier>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(supplierMapper).selectList(captor.capture());
        LambdaQueryWrapper<Supplier> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "供应商列表必须禁止无界 selectList：" + segment);
        assertTrue(segment.contains("LIKE"), "keyword 必须走模糊匹配而不是 status 精确过滤：" + segment);
        assertTrue(segment.contains("IS NULL"), "评分游标翻页必须覆盖 NULL 评分：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%acme%"), segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("ACTIVE"), segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900L), segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(new BigDecimal("4.5")), segment);
    }

    @Test
    @DisplayName("供应商列表：未取满一页时不误报截断")
    void untruncatedPageHasNoCursor() {
        when(supplierMapper.selectList(any())).thenReturn(List.of(supplier(2L, "3.8")));

        PageResult<Supplier> page = service.listSuppliers(
                1L, null, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertEquals(null, page.nextCursor());
    }

    private static Supplier supplier(Long id, String rating) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setShopId(1L);
        supplier.setSupplierName("supplier-" + id);
        supplier.setRating(new BigDecimal(rating));
        return supplier;
    }
}