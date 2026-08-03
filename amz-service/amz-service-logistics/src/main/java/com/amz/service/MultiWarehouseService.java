package com.amz.service;

import com.amz.model.*;

import java.util.List;
import java.util.Map;

/**
 * 多仓库存管理服务。
 */
public interface MultiWarehouseService {

    // ==================== 库存快照 ====================
    WarehouseStock saveStock(WarehouseStock stock);
    List<WarehouseStock> listStock(Long shopId, String sku, Long warehouseId);
    Map<String, Object> globalInventoryView(Long shopId, String sku);
    Map<String, Object> agingAnalysis(Long shopId);

    // ==================== 库存预警 ====================
    InventoryAlert createAlert(InventoryAlert alert);
    List<InventoryAlert> listAlerts(Long shopId, Boolean enabled);
    void toggleAlert(Long id, boolean enabled);
    Map<String, Object> checkAlerts(Long shopId);
}
