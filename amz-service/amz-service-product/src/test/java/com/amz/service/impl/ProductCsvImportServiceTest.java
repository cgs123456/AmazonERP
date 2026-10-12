package com.amz.service.impl;

import com.amz.dto.ProductCsvImportReport;
import com.amz.mapper.AmzProductMapper;
import com.amz.model.AmzProduct;
import com.amz.service.ProductMasterService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品 CSV 导入契约（A6）。
 * <p>
 * 锁四条不变量：
 * <ol>
 *   <li><b>缺必需列整批拒绝</b>——不做部分导入（部分导入会让调用方以为导全了）；</li>
 *   <li><b>已存在的 (shopId, sku, marketplaceId) 走更新而非重复插入</b>——
 *       同一份文件重复导入必须安全；</li>
 *   <li><b>行级隔离</b>——一行价格不是数字，只失败那一行且行号对着文件；</li>
 *   <li><b>空行算 skipped 不算 failed</b>——文件末尾空行不该让「1 条失败」掩盖真实问题。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("商品 CSV 导入：结构拒绝、幂等 upsert、行级隔离")
class ProductCsvImportServiceTest {

    @Mock
    private AmzProductMapper amzProductMapper;

    @Mock
    private ProductMasterService productMasterService;

    private ProductCsvImportService service;

    @BeforeAll
    static void initTableInfo() {
        // MyBatis-Plus 单测前置：不初始化表信息，LambdaQueryWrapper 会抛
        // 「can not find lambda cache」（本仓既有单测同样先做这一步）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AmzProduct.class);
    }

    @BeforeEach
    void setUp() {
        service = new ProductCsvImportService();
        // 手动注入（不用 @InjectMocks：service 只有一个 @Autowired 字段，显式更清楚）
        ReflectionTestUtils.setField(service, "productMasterService", productMasterService);
    }

    private ProductCsvImportReport importCsv(String csv) {
        return service.importCsv(1L, new BufferedReader(new StringReader(csv)));
    }

    @Test
    @DisplayName("缺必需列整批拒绝，且报告里没有任何一行被写入")
    void missingRequiredColumnRejectsWholeBatch() {
        String csv = "sku,brand\r\nSKU-1,Acme\r\n";

        org.junit.jupiter.api.Assertions.assertThrows(com.amz.util.CsvImportUtil.StructuralException.class,
                () -> importCsv(csv));

        verify(productMasterService, never()).create(any());
        verify(productMasterService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("新行走 create；同 (shopId, sku, marketplaceId) 再导入走 update")
    void upsertByShopSkuMarketplace() {
        String csv = "sku,marketplace_id,title\r\nSKU-1,ATVPDKIKX0DER,Widget\r\n";
        when(productMasterService.list(eq(1L), any(), any())).thenReturn(List.of());

        ProductCsvImportReport first = importCsv(csv);
        assertEquals(1, first.getInsertedRows());
        assertEquals(0, first.getUpdatedRows());
        verify(productMasterService).create(any());

        // 第二次导入同一行：list 返回已存在的行 → 走 update
        AmzProduct existing = new AmzProduct();
        existing.setId(77L);
        existing.setShopId(1L);
        existing.setSku("SKU-1");
        existing.setMarketplaceId("ATVPDKIKX0DER");
        when(productMasterService.list(eq(1L), any(), any())).thenReturn(List.of(existing));

        ProductCsvImportReport second = importCsv(csv);
        assertEquals(0, second.getInsertedRows());
        assertEquals(1, second.getUpdatedRows());
        verify(productMasterService).update(eq(1L), eq(77L), any());
    }

    @Test
    @DisplayName("一行价格不是数字只失败那一行，且行号对着文件（含表头行）")
    void badNumericFailsOnlyThatRowWithLineNumber() {
        String csv = "sku,marketplace_id,title,price\r\n"
                + "SKU-1,ATVPDKIKX0DER,Widget,19.99\r\n"
                + "SKU-2,ATVPDKIKX0DER,Badge,abc\r\n"
                + "SKU-3,ATVPDKIKX0DER,Gadget,5.00\r\n";
        when(productMasterService.list(eq(1L), any(), any())).thenReturn(List.of());

        ProductCsvImportReport report = importCsv(csv);

        assertEquals(2, report.getInsertedRows());
        assertEquals(1, report.getFailedRows());
        // 坏行是文件第 3 行（表头算第 1 行）
        assertNotNull(report.getErrors().get(3));
        assertTrue(report.getErrors().get(3).contains("abc"));
    }

    @Test
    @DisplayName("文件末尾空行算 skipped 不算 failed")
    void blankRowsAreSkippedNotFailed() {
        String csv = "sku,marketplace_id,title\r\n"
                + "SKU-1,ATVPDKIKX0DER,Widget\r\n"
                + ",,\r\n";
        when(productMasterService.list(eq(1L), any(), any())).thenReturn(List.of());

        ProductCsvImportReport report = importCsv(csv);

        assertEquals(1, report.getInsertedRows());
        assertEquals(1, report.getSkippedRows());
        assertEquals(0, report.getFailedRows());
        assertTrue(report.getErrors().isEmpty());
    }

    @Test
    @DisplayName("报告回显识别到的表头，便于排查列名不匹配")
    void reportEchoesHeaders() {
        String csv = "title,sku,marketplace_id\r\nWidget,SKU-1,ATVPDKIKX0DER\r\n";
        when(productMasterService.list(eq(1L), any(), any())).thenReturn(List.of());

        ProductCsvImportReport report = importCsv(csv);

        assertEquals(List.of("title", "sku", "marketplace_id"), report.getHeaders());
    }

    @Test
    @DisplayName("数值列正确落到实体上")
    void numericColumnsLandOnEntity() {
        String csv = "sku,marketplace_id,title,price,weight_g\r\n"
                + "SKU-1,ATVPDKIKX0DER,Widget,19.99,250\r\n";
        when(productMasterService.list(eq(1L), any(), any())).thenReturn(List.of());

        importCsv(csv);

        org.mockito.ArgumentCaptor<AmzProduct> captor =
                org.mockito.ArgumentCaptor.forClass(AmzProduct.class);
        verify(productMasterService).create(captor.capture());
        AmzProduct created = captor.getValue();
        assertEquals(new BigDecimal("19.99"), created.getPrice());
        assertEquals(250, created.getWeightG());
        assertFalse(created.getTitle().isBlank());
    }
}
