package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.mapper.PurchaseOrderMapper;
import com.amz.mapper.SupplierMapper;
import com.amz.mapper.SupplierProductMapper;
import com.amz.model.Supplier;
import com.amz.model.SupplierProduct;
import com.amz.result.PageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 供应商服务多租户隔离测试。
 * <p>
 * 回归：Body 中的 shopId 与按供应商 ID 的更新/关联操作此前没有资源归属校验，
 * 外部调用者可以把供应商或供应商-SKU 关系写到其他店铺。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("供应商服务多租户隔离测试")
class SupplierServiceImplTest {

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

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("创建供应商：拒绝请求体中的他店 shopId")
    void createSupplierRejectsForeignShopInBody() {
        authenticateForShop(1L);
        Supplier input = supplier(null, 2L);

        assertThrows(CodeErrorException.class, () -> service.createSupplier(input));
        verifyNoInteractions(supplierMapper);
    }

    @Test
    @DisplayName("更新供应商：拒绝他店资源且不写库")
    void updateSupplierRejectsForeignResource() {
        authenticateForShop(1L);
        when(supplierMapper.selectById(7L)).thenReturn(supplier(7L, 2L));
        Supplier input = supplier(7L, null);
        input.setSupplierName("hijacked");

        assertThrows(CodeErrorException.class, () -> service.updateSupplier(input));
        verify(supplierMapper, never()).updateById(any(Supplier.class));
    }

    @Test
    @DisplayName("更新供应商：禁止把 shopId 改到其他店铺")
    void updateSupplierRejectsShopIdMutation() {
        authenticateForShop(1L);
        when(supplierMapper.selectById(7L)).thenReturn(supplier(7L, 1L));
        Supplier input = supplier(7L, 2L);

        assertThrows(CodeErrorException.class, () -> service.updateSupplier(input));
        verify(supplierMapper, never()).updateById(any(Supplier.class));
    }

    @Test
    @DisplayName("更新供应商：请求体缺少 shopId 时锁定为原归属")
    void updateSupplierLocksExistingShopId() {
        authenticateForShop(1L);
        when(supplierMapper.selectById(7L)).thenReturn(supplier(7L, 1L));
        Supplier input = supplier(7L, null);
        input.setSupplierName("updated");

        Supplier result = service.updateSupplier(input);

        assertEquals(1L, result.getShopId());
        verify(supplierMapper).updateById(input);
    }

    @Test
    @DisplayName("更新供应商状态：拒绝他店资源且不写库")
    void updateSupplierStatusRejectsForeignResource() {
        authenticateForShop(1L);
        when(supplierMapper.selectById(7L)).thenReturn(supplier(7L, 2L));

        assertThrows(CodeErrorException.class, () -> service.updateSupplierStatus(7L, "DISABLED"));
        verify(supplierMapper, never()).updateById(any(Supplier.class));
    }

    @Test
    @DisplayName("查询供应商列表：拒绝他店 shopId")
    void listSuppliersRejectsForeignShop() {
        authenticateForShop(1L);

        assertThrows(CodeErrorException.class, () -> service.listSuppliers(2L, null, null, PageRequest.first(50)));
        verify(supplierMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("添加供应商-SKU：拒绝请求体中的他店 shopId")
    void addSupplierProductRejectsForeignShopInBody() {
        authenticateForShop(1L);
        SupplierProduct input = supplierProduct(1L, 9L);

        assertThrows(CodeErrorException.class, () -> service.addSupplierProduct(input));
        verify(supplierProductMapper, never()).insert(any(SupplierProduct.class));
    }

    @Test
    @DisplayName("添加供应商-SKU：拒绝把他店供应商挂到本店 SKU")
    void addSupplierProductRejectsForeignSupplierReference() {
        authenticateForShop(1L);
        when(supplierMapper.selectById(9L)).thenReturn(supplier(9L, 2L));
        SupplierProduct input = supplierProduct(1L, 9L);

        assertThrows(CodeErrorException.class, () -> service.addSupplierProduct(input));
        verify(supplierProductMapper, never()).insert(any(SupplierProduct.class));
    }

    @Test
    @DisplayName("查询 SKU 供应商：拒绝他店 shopId")
    void findSuppliersBySkuRejectsForeignShop() {
        authenticateForShop(1L);

        assertThrows(CodeErrorException.class, () -> service.findSuppliersBySku(2L, "SKU-1"));
        verify(supplierProductMapper, never()).selectList(any());
    }

    private static void authenticateForShop(Long shopId) {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(shopId));
    }

    private static Supplier supplier(Long id, Long shopId) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setShopId(shopId);
        supplier.setSupplierName("supplier-" + id);
        return supplier;
    }

    private static SupplierProduct supplierProduct(Long shopId, Long supplierId) {
        SupplierProduct product = new SupplierProduct();
        product.setShopId(shopId);
        product.setSupplierId(supplierId);
        product.setSku("SKU-1");
        return product;
    }
}