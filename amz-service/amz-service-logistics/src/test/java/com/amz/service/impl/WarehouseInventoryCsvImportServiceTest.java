package com.amz.service.impl;

import com.amz.dto.InventoryCsvImportReport;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.model.WarehouseInventory;
import com.amz.util.CsvImportUtil;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存元数据 CSV 导入契约（A6 第二个域）。
 *
 * <p>锁五条不变量：
 * <ol>
 *   <li><b>数量列被逐行拒绝</b>并指路到盘点/单据——批量改量绕开流水账是本仓 P1 要消灭的状态；</li>
 *   <li><b>找不到行不新建</b>——表无唯一键，find-or-create 会插重复行；</li>
 *   <li><b>命中多行不更新</b>——绝不替使用者挑一行；</li>
 *   <li><b>只按列改元数据</b>，不动 quantity（按列 UPDATE 的语义由 Mapper 测试外的真实库 IT 保证，
 *       这里断言的是「走 updateMetadataById、不走整行 update」；</li>
 *   <li><b>空行/未改动行算 skipped 不算 failed</b>。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("库存元数据 CSV 导入：数量列拒绝、不新建、不唯一拒绝、元数据按列")
class WarehouseInventoryCsvImportServiceTest {

    @Mock
    private WarehouseInventoryMapper inventoryMapper;

    private WarehouseInventoryCsvImportService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WarehouseInventory.class);
    }

    @BeforeEach
    void setUp() {
        service = new WarehouseInventoryCsvImportService();
        ReflectionTestUtils.setField(service, "inventoryMapper", inventoryMapper);
    }

    private InventoryCsvImportReport importCsv(String csv) {
        // 走 importCsv 而非直调 applyRow：行号「对着文件说」是产品承诺，必须被同一份测试覆盖
        return service.importCsv(1L, new BufferedReader(new StringReader(csv)));
    }

    private static WarehouseInventory existing(Long id, String asin, String location, LocalDate expire) {
        WarehouseInventory inv = new WarehouseInventory();
        inv.setId(id);
        inv.setShopId(1L);
        inv.setWarehouseId(10L);
        inv.setSku("SKU-A1");
        inv.setAsin(asin);
        inv.setLocationCode(location);
        inv.setExpireDate(expire);
        inv.setQuantity(100);
        return inv;
    }

    @Test
    @DisplayName("更新已存在行：只写元数据列，quantity 不进 SET")
    void updatesExistingRowOnlyMetadata() {
        when(inventoryMapper.selectList(any())).thenReturn(
                List.of(existing(55L, "B0OLD", "A-01", LocalDate.parse("2026-01-01"))));
        when(inventoryMapper.updateMetadataById(anyLong(), anyLong(), any(), any(), any())).thenReturn(1);

        InventoryCsvImportReport r = importCsv(
                "warehouse_id,sku,batch_no,asin,location_code,expire_date\r\n10,SKU-A1,,B0NEW,B-02,2026-12-31\r\n");

        assertEquals(1, r.getUpdatedRows());
        assertEquals(0, r.getFailedRows());
        verify(inventoryMapper).updateMetadataById(eq(55L), eq(1L), eq("B0NEW"), eq("B-02"),
                eq(LocalDate.parse("2026-12-31")));
        // 关键：绝不整行 update（会覆盖并发变更的 quantity）
        verify(inventoryMapper, never()).updateById(any(WarehouseInventory.class));
    }

    @Test
    @DisplayName("数量列出现即逐行拒绝并指路，不更新该行")
    void quantityColumnRejectedPerRow() {
        when(inventoryMapper.selectList(any())).thenReturn(List.of(existing(55L, "B0", "A", null)));

        InventoryCsvImportReport r = importCsv(
                "warehouse_id,sku,batch_no,quantity\r\n10,SKU-A1,,88\r\n");

        assertEquals(0, r.getUpdatedRows());
        assertEquals(1, r.getFailedRows());
        assertTrue(r.getErrors().get(2).contains("数量类列不能批量改"), r.getErrors().toString());
        assertTrue(r.getErrors().get(2).contains("盘点"), r.getErrors().toString());
        verify(inventoryMapper, never()).updateMetadataById(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("键值定位不到行：报「找不到」并指路先建库存，不新建")
    void notFoundDoesNotInsert() {
        when(inventoryMapper.selectList(any())).thenReturn(List.of());

        InventoryCsvImportReport r = importCsv(
                "warehouse_id,sku,batch_no,asin\r\n10,SKU-A1,,B0NEW\r\n");

        assertEquals(1, r.getFailedRows());
        assertTrue(r.getErrors().get(2).contains("找不到"), r.getErrors().toString());
        verify(inventoryMapper, never()).insert(any(WarehouseInventory.class));
        verify(inventoryMapper, never()).updateMetadataById(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("命中多行拒绝更新：绝不替使用者挑一行")
    void ambiguousMatchRejected() {
        when(inventoryMapper.selectList(any())).thenReturn(
                List.of(existing(55L, "B0", "A", null), existing(56L, "B0", "A", null)));

        InventoryCsvImportReport r = importCsv(
                "warehouse_id,sku,batch_no,asin\r\n10,SKU-A1,,B0NEW\r\n");

        assertEquals(1, r.getFailedRows());
        assertTrue(r.getErrors().get(2).contains("数据不唯一"), r.getErrors().toString());
        assertTrue(r.getErrors().get(2).contains("2 行"), r.getErrors().toString());
        verify(inventoryMapper, never()).updateMetadataById(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("无批次的行：batch_no 空值走 IS NULL 匹配语义；留空元数据列保持原值")
    void blankMetadataKeepsOriginal() {
        when(inventoryMapper.selectList(any())).thenReturn(
                List.of(existing(55L, "B0KEEP", "A-KEEP", LocalDate.parse("2026-01-01"))));
        when(inventoryMapper.updateMetadataById(anyLong(), anyLong(), any(), any(), any())).thenReturn(1);

        // 只改 asin，location/expire 留空 → 回填原值，不静默清空
        InventoryCsvImportReport r = importCsv(
                "warehouse_id,sku,batch_no,asin\r\n10,SKU-A1,,B0NEW\r\n");

        assertEquals(1, r.getUpdatedRows());
        verify(inventoryMapper).updateMetadataById(eq(55L), eq(1L), eq("B0NEW"), eq("A-KEEP"),
                eq(LocalDate.parse("2026-01-01")));
    }

    @Test
    @DisplayName("元数据全留空（无改动）→ skipped 不算 failed，也不写库")
    void unchangedRowSkipped() {
        when(inventoryMapper.selectList(any())).thenReturn(
                List.of(existing(55L, "B0", "A", LocalDate.parse("2026-01-01"))));

        InventoryCsvImportReport r = importCsv(
                "warehouse_id,sku,batch_no,asin,location_code\r\n10,SKU-A1,,B0,A\r\n");

        assertEquals(1, r.getSkippedRows());
        assertEquals(0, r.getFailedRows());
        assertEquals(0, r.getUpdatedRows());
        verify(inventoryMapper, never()).updateMetadataById(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("过期日期格式错→点名该行；库位码超长→点名且不写库")
    void validatesFormatAndLength() {
        when(inventoryMapper.selectList(any())).thenReturn(
                List.of(existing(55L, "B0", "A", null)));

        InventoryCsvImportReport bad = importCsv(
                "warehouse_id,sku,batch_no,expire_date\r\n10,SKU-A1,,2026-13-99\r\n");
        assertEquals(1, bad.getFailedRows());
        assertTrue(bad.getErrors().get(2).contains("yyyy-MM-dd"), bad.getErrors().toString());

        InventoryCsvImportReport longLoc = importCsv(
                "warehouse_id,sku,batch_no,location_code\r\n10,SKU-A1,," + "X".repeat(60) + "\r\n");
        assertEquals(1, longLoc.getFailedRows());
        assertTrue(longLoc.getErrors().get(2).contains("库位码"), longLoc.getErrors().toString());
        // 校验不过不写库（含不部分写：expire 格式错与超长各自单独挡住整行）
        verify(inventoryMapper, never()).updateMetadataById(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("缺 warehouse_id 列整批拒绝；回显表头；warehouse_id 非数字按行拒绝")
    void structuralAndBadKey() {
        // 缺 batch_no 列 → 结构错误（REQUIRED_COLUMNS）
        org.junit.jupiter.api.Assertions.assertThrows(
                com.amz.util.CsvImportUtil.StructuralException.class,
                () -> importCsv("warehouse_id,sku,asin\r\n10,SKU,B\r\n"));

        // warehouse_id 非整数 → 行级失败，不查库
        InventoryCsvImportReport r = importCsv(
                "warehouse_id,sku,batch_no,asin\r\nabc,SKU-A1,,B0NEW\r\n");
        assertEquals(1, r.getFailedRows());
        assertTrue(r.getErrors().get(2).contains("warehouse_id"), r.getErrors().toString());
    }

    @Test
    @DisplayName("多行混合：成功行更新、失败行带各自行号，互不阻挡")
    void mixedRowsIsolated() {
        when(inventoryMapper.selectList(any())).thenReturn(
                List.of(existing(55L, "B0", "A", null)), List.of());
        when(inventoryMapper.updateMetadataById(anyLong(), anyLong(), any(), any(), any())).thenReturn(1);

        InventoryCsvImportReport r = importCsv(
                "warehouse_id,sku,batch_no,asin\r\n"
                        + "10,SKU-A1,,B0NEW\r\n"        // 行2 命中 → 更新
                        + "10,SKU-MISSING,,B0X\r\n");  // 行3 找不到 → 失败

        assertEquals(1, r.getUpdatedRows());
        assertEquals(1, r.getFailedRows());
        assertNull(r.getErrors().get(2));
        assertTrue(r.getErrors().get(3).contains("找不到"), r.getErrors().toString());
        assertEquals(2, r.getTotalRows());
    }
}
