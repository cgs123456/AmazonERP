package com.amz.service.impl;

import com.amz.dto.SupplierPriceCsvImportReport;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.SupplierProduct;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供货价 CSV 导入契约（A6 第三个域）。
 *
 * <p>锁五条不变量：
 * <ol>
 *   <li><b>身份/状态/is_preferred 列被逐行拒绝</b>并指路单条表单——设首选有跨行副作用，
 *       放进逐行循环里做跨行改写既慢又易错；</li>
 *   <li><b>找不到不新建</b>——表无唯一键，且新建走的是纯 insert 的 addSupplierProduct
 *       （语义是「新增供货关系」不是「改价」），不能当 upsert；</li>
 *   <li><b>命中多行拒绝</b>，绝不替使用者挑一行；</li>
 *   <li><b>只按列改价</b>（走 updatePriceById，不整行 update、不动 is_preferred）；</li>
 *   <li><b>空值保持原值</b>、全空算 skipped 不算 failed、数值格式错按行报。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("供货价 CSV 导入：身份/首选列拒绝、不新建、不唯一拒绝、按列更新")
class SupplierPriceCsvImportServiceTest {

    @Mock
    private SupplierProductMapper supplierProductMapper;

    private SupplierPriceCsvImportService service;

    @BeforeAll
    static void initTableInfo() {
        // MyBatis-Plus 单测前置：不初始化表信息，LambdaQueryWrapper 抛
        // 「can not find lambda cache」（本仓既有单测同样先做这一步）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SupplierProduct.class);
    }

    @BeforeEach
    void setUp() {
        service = new SupplierPriceCsvImportService();
        ReflectionTestUtils.setField(service, "supplierProductMapper", supplierProductMapper);
    }

    private SupplierPriceCsvImportReport importCsv(String csv) {
        return service.importCsv(1L, 2L, new BufferedReader(new StringReader(csv)));
    }

    private static SupplierProduct existing(Long id, String sku, String price) {
        SupplierProduct sp = new SupplierProduct();
        sp.setId(id);
        sp.setShopId(1L);
        sp.setSupplierId(2L);
        sp.setSku(sku);
        sp.setSupplyPrice(new BigDecimal(price));
        sp.setMoq(1);
        sp.setLeadTimeDays(7);
        sp.setSupplierSkuCode("OLD-CODE");
        return sp;
    }

    @Test
    @DisplayName("更新已存在供货关系：只按列改价，is_preferred 不进 SET")
    void updatesExistingRowOnlyPrice() {
        when(supplierProductMapper.selectList(any())).thenReturn(
                List.of(existing(70L, "SKU-A1", "12.00")));
        when(supplierProductMapper.updatePriceById(any())).thenReturn(1);

        SupplierPriceCsvImportReport r = importCsv(
                "sku,supply_price,moq\r\nSKU-A1,13.50,20\r\n");

        assertEquals(1, r.getUpdatedRows());
        assertEquals(0, r.getFailedRows());
        ArgumentCaptor<SupplierProduct> cap = ArgumentCaptor.forClass(SupplierProduct.class);
        verify(supplierProductMapper).updatePriceById(cap.capture());
        SupplierProduct patch = cap.getValue();
        assertEquals(new BigDecimal("13.50"), patch.getSupplyPrice());
        assertEquals(20, patch.getMoq());
        // 身份列由请求参数带入
        assertEquals(1L, patch.getShopId());
        assertEquals(2L, patch.getSupplierId());
        // 刻意不动：没有 is_preferred 更新路径（走的是按列 update，不含该列）
        verify(supplierProductMapper, never()).updateById(any(SupplierProduct.class));
    }

    @Test
    @DisplayName("文件带 is_preferred/身份/状态列即逐行拒绝并指路，不更新")
    void forbiddenColumnsRejectedPerRow() {
        when(supplierProductMapper.selectList(any())).thenReturn(
                List.of(existing(70L, "SKU-A1", "12.00")));

        SupplierPriceCsvImportReport r = importCsv(
                "sku,supply_price,is_preferred\r\nSKU-A1,13.50,1\r\n");

        assertEquals(0, r.getUpdatedRows());
        assertEquals(1, r.getFailedRows());
        assertTrue(r.getErrors().get(2).contains("is_preferred"), r.getErrors().toString());
        assertTrue(r.getErrors().get(2).contains("单条表单"), r.getErrors().toString());
        verify(supplierProductMapper, never()).updatePriceById(any());
    }

    @Test
    @DisplayName("键值定位不到：报「找不到」并指路先建供货关系，不新建")
    void notFoundDoesNotInsert() {
        when(supplierProductMapper.selectList(any())).thenReturn(List.of());

        SupplierPriceCsvImportReport r = importCsv("sku,supply_price\r\nSKU-GHOST,9.99\r\n");

        assertEquals(1, r.getFailedRows());
        assertTrue(r.getErrors().get(2).contains("找不到"), r.getErrors().toString());
        verify(supplierProductMapper, never()).insert(any(SupplierProduct.class));
        verify(supplierProductMapper, never()).updatePriceById(any());
    }

    @Test
    @DisplayName("命中多行拒绝：绝不替使用者挑一行")
    void ambiguousMatchRejected() {
        when(supplierProductMapper.selectList(any())).thenReturn(
                List.of(existing(70L, "SKU-A1", "12.00"), existing(71L, "SKU-A1", "13.00")));

        SupplierPriceCsvImportReport r = importCsv("sku,supply_price\r\nSKU-A1,14.00\r\n");

        assertEquals(1, r.getFailedRows());
        assertTrue(r.getErrors().get(2).contains("数据不唯一"), r.getErrors().toString());
        assertTrue(r.getErrors().get(2).contains("2 行"), r.getErrors().toString());
        verify(supplierProductMapper, never()).updatePriceById(any());
    }

    @Test
    @DisplayName("留空的价格/字段保持原值（回填目标行，不静默清空）")
    void blankPriceKeepsOriginal() {
        when(supplierProductMapper.selectList(any())).thenReturn(
                List.of(existing(70L, "SKU-A1", "12.00")));
        when(supplierProductMapper.updatePriceById(any())).thenReturn(1);

        // 只改 moq，supply_price 留空 → 应保持 12.00
        SupplierPriceCsvImportReport r = importCsv("sku,moq\r\nSKU-A1,50\r\n");

        assertEquals(1, r.getUpdatedRows());
        ArgumentCaptor<SupplierProduct> cap = ArgumentCaptor.forClass(SupplierProduct.class);
        verify(supplierProductMapper).updatePriceById(cap.capture());
        assertEquals(new BigDecimal("12.00"), cap.getValue().getSupplyPrice());
        assertEquals(50, cap.getValue().getMoq());
        // supplier_sku_code 留空 → 保持原值 OLD-CODE
        assertEquals("OLD-CODE", cap.getValue().getSupplierSkuCode());
    }

    @Test
    @DisplayName("价格不是数字/为负按行报，不写库")
    void rejectsBadNumbers() {
        when(supplierProductMapper.selectList(any())).thenReturn(
                List.of(existing(70L, "SKU-A1", "12.00")));

        SupplierPriceCsvImportReport r = importCsv("sku,supply_price\r\nSKU-A1,abc\r\n");
        assertEquals(1, r.getFailedRows());
        assertTrue(r.getErrors().get(2).contains("不是合法数字"), r.getErrors().toString());
        verify(supplierProductMapper, never()).updatePriceById(any());
    }

    @Test
    @DisplayName("字段全留空（无改动）→ skipped 不算 failed，不写库")
    void unchangedRowSkipped() {
        when(supplierProductMapper.selectList(any())).thenReturn(
                List.of(existing(70L, "SKU-A1", "12.00")));

        SupplierPriceCsvImportReport r = importCsv(
                "sku,supply_price,moq\r\nSKU-A1,12.00,1\r\n");

        assertEquals(1, r.getSkippedRows());
        assertEquals(0, r.getUpdatedRows());
        assertEquals(0, r.getFailedRows());
        verify(supplierProductMapper, never()).updatePriceById(any());
    }

    @Test
    @DisplayName("缺 sku 整批拒绝（结构错误）")
    void missingRequiredColumnStructural() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.amz.util.CsvImportUtil.StructuralException.class,
                () -> importCsv("supply_price,moq\r\n12.00,5\r\n"));
    }

    @Test
    @DisplayName("shopId/supplierId 为空直接拒绝")
    void nullIdsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.importCsv(null, 2L,
                        new BufferedReader(new StringReader("sku\r\nSKU-A1\r\n"))));
    }

    @Test
    @DisplayName("混合行：成功更新、失败行带各自行号，互不阻挡；totalRows 含两者")
    void mixedRowsIsolated() {
        when(supplierProductMapper.selectList(any())).thenReturn(
                List.of(existing(70L, "SKU-A1", "12.00")), List.of());
        when(supplierProductMapper.updatePriceById(any())).thenReturn(1);

        SupplierPriceCsvImportReport r = importCsv(
                "sku,supply_price\r\n"
                        + "SKU-A1,13.00\r\n"     // 行2 命中 → 更新
                        + "SKU-GHOST,9.00\r\n"); // 行3 找不到 → 失败

        assertEquals(1, r.getUpdatedRows());
        assertEquals(1, r.getFailedRows());
        assertNull(r.getErrors().get(2));
        assertTrue(r.getErrors().get(3).contains("找不到"), r.getErrors().toString());
        assertEquals(2, r.getTotalRows());
    }
}
