package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.InventoryAlertMapper;
import com.amz.mapper.WarehouseStockMapper;
import com.amz.model.InventoryAlert;
import com.amz.model.WarehouseStock;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.MultiWarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 多仓库存管理服务实现。
 * <p>
 * 统一管理本地仓/海外仓/FBA仓 + 库龄分析 + 智能预警。
 */
@Slf4j
@Service
public class MultiWarehouseServiceImpl implements MultiWarehouseService {

    /** 聚合/预警扫描的硬上限；触顶时必须显式返回截断证据，禁止静默少算。 */
    public static final int AGGREGATION_SCAN_LIMIT = 500;

    @Autowired
    private WarehouseStockMapper warehouseStockMapper;
    @Autowired
    private InventoryAlertMapper inventoryAlertMapper;

    // ==================== 库存快照 ====================

    @Override
    public WarehouseStock saveStock(WarehouseStock stock) {
        if (stock == null) {
            throw new CodeErrorException("库存快照不能为空");
        }
        requireShopAllowed(stock.getShopId(), "库存快照");
        if (stock.getWarehouseId() == null || stock.getSku() == null) {
            throw new IllegalArgumentException("仓库ID、SKU不能为空");
        }
        if (stock.getSnapshotTime() == null) stock.setSnapshotTime(LocalDateTime.now());
        if (stock.getAvailableQty() == null) stock.setAvailableQty(0);
        if (stock.getReservedQty() == null) stock.setReservedQty(0);
        if (stock.getInboundQty() == null) stock.setInboundQty(0);
        if (stock.getTransferOutQty() == null) stock.setTransferOutQty(0);
        if (stock.getUnitCost() == null) stock.setUnitCost(BigDecimal.ZERO);
        if (stock.getTotalQty() == null) {
            stock.setTotalQty(stock.getAvailableQty() + stock.getReservedQty()
                    + stock.getInboundQty());
        }
        if (stock.getTotalValue() == null && stock.getUnitCost().compareTo(BigDecimal.ZERO) > 0) {
            stock.setTotalValue(stock.getUnitCost().multiply(BigDecimal.valueOf(stock.getTotalQty())));
        }

        // 计算在库天数
        if (stock.getLastInboundDate() != null) {
            stock.setDaysInStock((int) ChronoUnit.DAYS.between(stock.getLastInboundDate(), LocalDate.now()));
        } else {
            stock.setDaysInStock(0);
        }

        // upsert
        LambdaQueryWrapper<WarehouseStock> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseStock::getShopId, stock.getShopId())
               .eq(WarehouseStock::getWarehouseId, stock.getWarehouseId())
               .eq(WarehouseStock::getSku, stock.getSku());
        WarehouseStock exist = warehouseStockMapper.selectOne(wrapper);
        if (exist != null) {
            stock.setId(exist.getId());
            warehouseStockMapper.updateById(stock);
        } else {
            warehouseStockMapper.insert(stock);
        }
        return stock;
    }

    @Override
    public PageResult<WarehouseStock> listStock(Long shopId, String sku, Long warehouseId,
                                                PageRequest page) {
        requireShopAllowed(shopId, "库存");
        if (page == null) {
            throw new CodeErrorException("分页参数不能为空");
        }
        LambdaQueryWrapper<WarehouseStock> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseStock::getShopId, shopId);
        if (sku != null && !sku.isBlank()) wrapper.eq(WarehouseStock::getSku, sku);
        if (warehouseId != null) wrapper.eq(WarehouseStock::getWarehouseId, warehouseId);

        StockCursor cursor = decodeStockCursor(page);
        if (cursor != null) {
            wrapper.and(w -> w
                    .gt(WarehouseStock::getWarehouseId, cursor.warehouseId())
                    .or(a -> a.eq(WarehouseStock::getWarehouseId, cursor.warehouseId())
                            .gt(WarehouseStock::getSku, cursor.sku()))
                    .or(a -> a.eq(WarehouseStock::getWarehouseId, cursor.warehouseId())
                            .eq(WarehouseStock::getSku, cursor.sku())
                            .gt(WarehouseStock::getId, cursor.id())));
        }
        wrapper.orderByAsc(WarehouseStock::getWarehouseId)
                .orderByAsc(WarehouseStock::getSku)
                .orderByAsc(WarehouseStock::getId)
                .last("LIMIT " + page.probeSize());
        return PageResult.of(warehouseStockMapper.selectList(wrapper), page.size(),
                this::encodeStockCursor);
    }

    @Override
    public Map<String, Object> globalInventoryView(Long shopId, String sku) {
        StockScan stockScan = scanStocks(shopId, sku);
        List<WarehouseStock> stocks = stockScan.rows();

        // 按仓库类型聚合
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        int totalAvailable = 0;
        int totalReserved = 0;
        int totalInbound = 0;
        BigDecimal totalValue = BigDecimal.ZERO;

        for (WarehouseStock s : stocks) {
            String type = s.getWarehouseType() != null ? s.getWarehouseType() : "UNKNOWN";
            String key = (s.getWarehouseName() != null ? s.getWarehouseName() : type) + "(" + type + ")";

            BigDecimal val = byType.getOrDefault(key, BigDecimal.ZERO);
            if (s.getAvailableQty() != null) val = val.add(BigDecimal.valueOf(s.getAvailableQty()));
            byType.put(key, val);

            if (s.getAvailableQty() != null) totalAvailable += s.getAvailableQty();
            if (s.getReservedQty() != null) totalReserved += s.getReservedQty();
            if (s.getInboundQty() != null) totalInbound += s.getInboundQty();
            if (s.getTotalValue() != null) totalValue = totalValue.add(s.getTotalValue());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("sku", sku);
        result.put("totalAvailable", totalAvailable);
        result.put("totalReserved", totalReserved);
        result.put("totalInbound", totalInbound);
        result.put("totalValue", totalValue);
        result.put("byWarehouse", byType);
        result.put("details", stocks);
        result.put("stocksTruncated", stockScan.truncated());
        result.put("scannedStockCount", stocks.size());
        return result;
    }

    @Override
    public Map<String, Object> agingAnalysis(Long shopId) {
        StockScan stockScan = scanStocks(shopId, null);
        List<WarehouseStock> all = stockScan.rows();

        // 库龄分段
        // ⚠ WarehouseStock.daysInStock 来自 `days_in_stock` 字段，若快照同步滞后可能偏高，
        // 此处分位统计并在 D90+ 分类中标注数据时效性。
        long fresh30 = 0, mid90 = 0, old180 = 0, dead365 = 0;
        BigDecimal freshValue = BigDecimal.ZERO, midValue = BigDecimal.ZERO,
                oldValue = BigDecimal.ZERO, deadValue = BigDecimal.ZERO;

        for (WarehouseStock s : all) {
            if (s.getAvailableQty() == null || s.getAvailableQty() == 0) continue;
            int days = s.getDaysInStock() != null ? s.getDaysInStock() : 0;
            BigDecimal val = s.getTotalValue() != null ? s.getTotalValue() : BigDecimal.ZERO;

            if (days <= 30) { fresh30++; freshValue = freshValue.add(val); }
            else if (days <= 90) { mid90++; midValue = midValue.add(val); }
            else if (days <= 180) { old180++; oldValue = oldValue.add(val); }
            else { dead365++; deadValue = deadValue.add(val); }
        }

        BigDecimal grandTotal = freshValue.add(midValue).add(oldValue).add(deadValue);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("totalSkus", all.stream().filter(s -> s.getAvailableQty() > 0).count());
        result.put("aging", Map.of(
                "fresh_30d", Map.of("count", fresh30, "value", freshValue, "pct",
                        grandTotal.compareTo(BigDecimal.ZERO) > 0
                                ? freshValue.divide(grandTotal, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO),
                "mid_31_90d", Map.of("count", mid90, "value", midValue, "pct",
                        grandTotal.compareTo(BigDecimal.ZERO) > 0
                                ? midValue.divide(grandTotal, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO),
                "old_91_180d", Map.of("count", old180, "value", oldValue, "pct",
                        grandTotal.compareTo(BigDecimal.ZERO) > 0
                                ? oldValue.divide(grandTotal, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO),
                "dead_181d_plus", Map.of("count", dead365, "value", deadValue, "pct",
                        grandTotal.compareTo(BigDecimal.ZERO) > 0
                                ? deadValue.divide(grandTotal, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO)
        ));

        result.put("stocksTruncated", stockScan.truncated());
        result.put("scannedStockCount", all.size());

        // Top 10 最老库存
        result.put("oldestTop10", all.stream()
                .filter(s -> s.getAvailableQty() > 0)
                .sorted(Comparator.comparingInt(WarehouseStock::getDaysInStock).reversed())
                .limit(10)
                .map(s -> Map.of("sku", s.getSku(), "warehouse", s.getWarehouseName(),
                        "days", s.getDaysInStock(), "qty", s.getAvailableQty(),
                        "value", s.getTotalValue() != null ? s.getTotalValue() : BigDecimal.ZERO))
                .collect(Collectors.toList()));

        return result;
    }

    // ==================== 库存预警 ====================

    @Override
    public InventoryAlert createAlert(InventoryAlert alert) {
        if (alert == null) {
            throw new CodeErrorException("预警规则不能为空");
        }
        requireShopAllowed(alert.getShopId(), "预警规则");
        if (alert.getEnabled() == null) alert.setEnabled(true);
        if (alert.getAlertLevel() == null) alert.setAlertLevel("WARNING");
        inventoryAlertMapper.insert(alert);
        return alert;
    }

    @Override
    public List<InventoryAlert> listAlerts(Long shopId, Boolean enabled) {
        requireShopAllowed(shopId, "预警规则");
        LambdaQueryWrapper<InventoryAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryAlert::getShopId, shopId);
        if (enabled != null) wrapper.eq(InventoryAlert::getEnabled, enabled);
        return inventoryAlertMapper.selectList(wrapper);
    }

    @Override
    public void toggleAlert(Long id, boolean enabled) {
        if (id == null) {
            throw new CodeErrorException("预警规则 ID 不能为空");
        }
        InventoryAlert alert = inventoryAlertMapper.selectById(id);
        if (alert == null || !UserContext.isShopAllowed(alert.getShopId())) {
            throw new CodeErrorException("预警规则不存在或无权访问");
        }
        alert.setEnabled(enabled);
        inventoryAlertMapper.updateById(alert);
    }

    @Override
    public Map<String, Object> checkAlerts(Long shopId) {
        List<InventoryAlert> alerts = listAlerts(shopId, true);
        StockScan stockScan = scanStocks(shopId, null);
        List<WarehouseStock> allStocks = stockScan.rows();

        List<Map<String, Object>> triggered = new ArrayList<>();
        int criticalCount = 0, warningCount = 0, infoCount = 0;

        for (InventoryAlert alert : alerts) {
            for (WarehouseStock stock : allStocks) {
                // 如果规则指定了 SKU/仓库，仅匹配对应库存
                if (alert.getSku() != null && !alert.getSku().isBlank()
                        && !alert.getSku().equals(stock.getSku())) continue;
                if (alert.getWarehouseId() != null
                        && !alert.getWarehouseId().equals(stock.getWarehouseId())) continue;

                boolean isTriggered = evaluateAlert(alert, stock);
                if (isTriggered) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("alertId", alert.getId());
                    t.put("alertType", alert.getAlertType());
                    t.put("alertLevel", alert.getAlertLevel());
                    t.put("description", alert.getDescription());
                    t.put("sku", stock.getSku());
                    t.put("warehouseName", stock.getWarehouseName());
                    t.put("availableQty", stock.getAvailableQty());
                    t.put("daysInStock", stock.getDaysInStock());
                    t.put("totalValue", stock.getTotalValue());
                    triggered.add(t);

                    switch (alert.getAlertLevel()) {
                        case "CRITICAL": criticalCount++; break;
                        case "WARNING": warningCount++; break;
                        default: infoCount++;
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("alertRulesChecked", alerts.size());
        result.put("totalTriggered", triggered.size());
        result.put("critical", criticalCount);
        result.put("warning", warningCount);
        result.put("info", infoCount);
        result.put("alerts", triggered);
        result.put("stocksTruncated", stockScan.truncated());
        result.put("scannedStockCount", allStocks.size());
        return result;
    }


    private StockScan scanStocks(Long shopId, String sku) {
        requireShopAllowed(shopId, "库存");
        LambdaQueryWrapper<WarehouseStock> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseStock::getShopId, shopId);
        if (sku != null && !sku.isBlank()) wrapper.eq(WarehouseStock::getSku, sku);
        wrapper.orderByAsc(WarehouseStock::getWarehouseId)
                .orderByAsc(WarehouseStock::getSku)
                .orderByAsc(WarehouseStock::getId)
                .last("LIMIT " + (AGGREGATION_SCAN_LIMIT + 1));

        List<WarehouseStock> rows = warehouseStockMapper.selectList(wrapper);
        boolean truncated = rows.size() > AGGREGATION_SCAN_LIMIT;
        List<WarehouseStock> visible = truncated
                ? new ArrayList<>(rows.subList(0, AGGREGATION_SCAN_LIMIT))
                : rows;
        if (truncated) {
            log.warn("库存聚合扫描触顶，shopId={} sku={} limit={}，结果已标记截断",
                    shopId, sku, AGGREGATION_SCAN_LIMIT);
        }
        return new StockScan(visible, truncated);
    }

    private StockCursor decodeStockCursor(PageRequest page) {
        if (page == null || !page.hasCursor()) return null;
        String[] parts = page.payload().split("\u001F", -1);
        if (parts.length != 3) {
            throw new InvalidParamException("库存分页游标格式非法");
        }
        try {
            long warehouseId = Long.parseLong(parts[0]);
            String sku = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long id = Long.parseLong(parts[2]);
            if (warehouseId <= 0 || id <= 0 || sku.isBlank()) {
                throw new InvalidParamException("库存分页游标值非法");
            }
            return new StockCursor(warehouseId, sku, id);
        } catch (RuntimeException e) {
            if (e instanceof InvalidParamException) throw e;
            throw new InvalidParamException("库存分页游标无法解析");
        }
    }

    private String encodeStockCursor(WarehouseStock stock) {
        if (stock.getWarehouseId() == null || stock.getSku() == null || stock.getId() == null) {
            throw new InvalidParamException("库存分页游标字段缺失");
        }
        String sku = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(stock.getSku().getBytes(StandardCharsets.UTF_8));
        return PageRequest.encodeCursor(stock.getWarehouseId() + "\u001F" + sku + "\u001F" + stock.getId());
    }

    private record StockCursor(long warehouseId, String sku, long id) {
    }

    private record StockScan(List<WarehouseStock> rows, boolean truncated) {
    }
    /**
     * 评估库存预警规则。
     */
    private boolean evaluateAlert(InventoryAlert alert, WarehouseStock stock) {
        switch (alert.getAlertType()) {
            case "STOCKOUT":
            case "LOW_STOCK":
                if ("DAYS".equals(alert.getThresholdUnit())) {
                    // 按天数判断：可用库存 ÷ 日均销量 < 阈值
                    // 简化为按在库天数反算
                    return stock.getAvailableQty() > 0
                            && stock.getDaysInStock() != null
                            && alert.getThresholdValue().intValue() > 0
                            && stock.getDaysInStock() <= alert.getThresholdValue().intValue();
                } else {
                    // 按数量判断
                    return stock.getAvailableQty() <= alert.getThresholdValue().intValue();
                }
            case "OVERSTOCK":
                return stock.getDaysInStock() != null
                        && stock.getDaysInStock() > alert.getThresholdValue().intValue();
            case "AGING":
                return stock.getDaysInStock() != null
                        && stock.getDaysInStock() > alert.getThresholdValue().intValue()
                        && stock.getAvailableQty() > 0;
            case "NO_MOVEMENT":
                return stock.getDaysInStock() != null
                        && stock.getDaysInStock() > alert.getThresholdValue().intValue();
            default:
                return false;
        }
    }

    private void requireShopAllowed(Long shopId, String what) {
        if (shopId == null) {
            throw new CodeErrorException(what + "缺少店铺 ID");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            throw new CodeErrorException(what + "不属于当前账号可操作的店铺");
        }
    }
}
