package com.amz.service;

import com.amz.model.Supplier;
import com.amz.model.SupplierProduct;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 供应商管理服务接口。
 */
public interface SupplierService {

    /** 创建供应商 */
    Supplier createSupplier(Supplier supplier);

    /** 更新供应商信息 */
    Supplier updateSupplier(Supplier supplier);

    /** 查询店铺供应商列表 */
    List<Supplier> listSuppliers(Long shopId, String status);

    /** 获取供应商详情 */
    Supplier getSupplier(Long id);

    /** 禁用/拉黑供应商 */
    boolean updateSupplierStatus(Long id, String status);

    /** 添加供应商-SKU 关联 */
    SupplierProduct addSupplierProduct(SupplierProduct supplierProduct);

    /** 查询 SKU 的供应商列表（按首选排序） */
    List<SupplierProduct> findSuppliersBySku(Long shopId, String sku);

    /** 多供应商比价：返回供应商报价列表按价格升序 */
    List<Map<String, Object>> compareSupplierPrices(Long shopId, String sku);

    /** 计算供应商 KPI（准时交货率/质量合格率/综合评分） */
    Map<String, Object> calculateSupplierKpi(Long supplierId);

    /** 更新供应商统计数据（采购单完成时调用） */
    void updateSupplierStats(Long supplierId, BigDecimal orderAmount, boolean onTime, boolean qualityPass);
}
