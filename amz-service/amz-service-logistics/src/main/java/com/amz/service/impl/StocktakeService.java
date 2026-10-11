package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.StocktakeItemMapper;
import com.amz.mapper.StocktakeMapper;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.model.InventoryMovement;
import com.amz.model.Stocktake;
import com.amz.model.StocktakeItem;
import com.amz.model.WarehouseInventory;
import com.amz.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 库存盘点服务（账实核对）。
 * <p>
 * <b>存在动因</b>：标准跨境 ERP（领星/wimoor 类）均有盘点——按仓库+SKU 快照账面 →
 * 录实盘 → 差异审核 → 按实盘修正并留痕。本仓此前账实不符只能手工改库：
 * 无单据、无留痕、无审批，等于没有盘点能力。
 * <p>
 * <b>状态机</b>（白名单强制）：DRAFT →(录入实盘)→ COUNTED →(修正落账)→ APPLIED；
 * DRAFT/COUNTED 可 CANCELLED。修正只发生在 APPLIED 一步。
 * <p>
 * <b>修正语义（关键）</b>：以「<b>apply 时的真实账面 → 实盘值</b>」写流水并 SET 库存，
 * 而不是按「建单快照的差额」调——快照到 apply 之间账面可能已被出入库改变，
 * 按快照调会调错。快照差额只用于差异报告。
 * <p>
 * <b>未盘不按 0</b>：{@code countedQty == null} 的明细行在 apply 时跳过并计数——
 * 「没盘」与「盘为 0」是两件事，按 0 会把库存清空。
 */
@Slf4j
@Service
public class StocktakeService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Autowired
    private StocktakeMapper stocktakeMapper;

    @Autowired
    private StocktakeItemMapper stocktakeItemMapper;

    @Autowired
    private WarehouseInventoryMapper inventoryMapper;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private InventoryMovementService movementService;

    /**
     * 建单：按仓库+SKU 快照当前账面数。
     */
    @Transactional(rollbackFor = Exception.class)
    public Stocktake create(Long shopId, Long warehouseId, String remark, List<String> skus) {
        LogisticsShopGuard.requireShopAllowed(shopId, "盘点单");
        if (warehouseId == null) {
            throw new CodeErrorException("盘点仓库不能为空");
        }
        if (skus == null || skus.isEmpty()) {
            throw new CodeErrorException("盘点明细不能为空（至少一个 SKU）");
        }
        Set<String> distinct = new HashSet<>();
        Stocktake stocktake = new Stocktake();
        stocktake.setShopId(shopId);
        stocktake.setWarehouseId(warehouseId);
        stocktake.setStatus(Stocktake.STATUS_DRAFT);
        stocktake.setRemark(remark);
        stocktake.setOperatorId(UserContext.getUserId() == null ? null : UserContext.getUserId().longValue());
        stocktake.setStocktakeNo(generateNo());
        stocktakeMapper.insert(stocktake);

        for (String raw : skus) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String sku = raw.trim();
            if (!distinct.add(sku)) {
                throw new CodeErrorException("盘点明细存在重复 SKU：" + sku);
            }
            StocktakeItem item = new StocktakeItem();
            item.setStocktakeId(stocktake.getId());
            item.setShopId(shopId);
            item.setSku(sku);
            item.setExpectedQty(warehouseService.currentQuantity(warehouseId, shopId, sku));
            item.setCountedQty(null);
            stocktakeItemMapper.insert(item);
        }
        if (distinct.isEmpty()) {
            throw new CodeErrorException("盘点明细不能为空（SKU 全为空串）");
        }
        log.info("创建盘点单 stocktakeNo={} shopId={} warehouse={} 行数={}",
                stocktake.getStocktakeNo(), shopId, warehouseId, distinct.size());
        return stocktake;
    }

    /**
     * 录入实盘（DRAFT/COUNTED 可反复录入修正；不影响账面）。
     *
     * @param counts sku → 实盘数量（必须 ≥ 0）
     */
    @Transactional(rollbackFor = Exception.class)
    public Stocktake count(Long stocktakeId, List<CountLine> counts) {
        Stocktake stocktake = load(stocktakeId, "盘点单");
        if (Stocktake.STATUS_APPLIED.equals(stocktake.getStatus())
                || Stocktake.STATUS_CANCELLED.equals(stocktake.getStatus())) {
            throw new CodeErrorException("盘点单当前状态为 " + stocktake.getStatus() + "，不可录入实盘");
        }
        if (counts == null || counts.isEmpty()) {
            throw new CodeErrorException("实盘明细不能为空");
        }
        List<StocktakeItem> items = stocktakeItemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                .eq(StocktakeItem::getStocktakeId, stocktakeId));
        Set<String> known = new HashSet<>();
        for (StocktakeItem item : items) {
            known.add(item.getSku());
        }
        for (CountLine line : counts) {
            if (line == null || !StringUtils.hasText(line.sku())) {
                throw new CodeErrorException("实盘明细缺少 SKU");
            }
            if (line.countedQty() == null || line.countedQty() < 0) {
                throw new CodeErrorException("实盘数量必须 ≥ 0：sku=" + line.sku());
            }
            if (!known.contains(line.sku().trim())) {
                throw new CodeErrorException("SKU 不在盘点单内：" + line.sku() + "（如需补盘请新建盘点单）");
            }
        }
        for (CountLine line : counts) {
            StocktakeItem item = items.stream()
                    .filter(i -> i.getSku().equals(line.sku().trim()))
                    .findFirst().orElseThrow();
            item.setCountedQty(line.countedQty());
            stocktakeItemMapper.updateById(item);
        }
        stocktake.setStatus(Stocktake.STATUS_COUNTED);
        stocktakeMapper.updateById(stocktake);
        log.info("盘点录入实盘 stocktakeNo={} 行数={}", stocktake.getStocktakeNo(), counts.size());
        return stocktake;
    }

    /**
     * 修正落账（COUNTED → APPLIED）：按实盘 SET 库存，逐行写 STOCKTAKE_ADJUST 流水。
     *
     * @return 修正统计（修正行数 / 跳过未盘行数 / 零差异数）
     */
    @Transactional(rollbackFor = Exception.class)
    public ApplyResult apply(Long stocktakeId) {
        Stocktake stocktake = load(stocktakeId, "盘点单");
        if (!Stocktake.STATUS_COUNTED.equals(stocktake.getStatus())) {
            throw new CodeErrorException("只有已录入实盘（COUNTED）的盘点单可修正落账，当前 "
                    + stocktake.getStatus());
        }
        List<StocktakeItem> items = stocktakeItemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                .eq(StocktakeItem::getStocktakeId, stocktakeId));
        int adjusted = 0;
        int skippedUncounted = 0;
        int noDiff = 0;
        for (StocktakeItem item : items) {
            if (item.getCountedQty() == null) {
                // 「没盘」与「盘为 0」是两件事：未盘行跳过，绝不按 0 清库存
                skippedUncounted++;
                continue;
            }
            // before 取 apply 时的真实账面（不是建单快照——期间可能已有出入库）
            int before = warehouseService.currentQuantity(
                    stocktake.getWarehouseId(), stocktake.getShopId(), item.getSku());
            int target = item.getCountedQty();
            if (before == target) {
                noDiff++;
                continue;
            }
            WarehouseInventory row = findRow(stocktake.getWarehouseId(), stocktake.getShopId(), item.getSku());
            if (row == null) {
                // 建单时有快照（可能为 0），apply 时行被删：无行可 SET，明确失败而不是静默跳过
                throw new CodeErrorException("库存行已不存在，无法修正：sku=" + item.getSku()
                        + "（请重新建盘）");
            }
            int affected = inventoryMapper.adjustQuantityTo(row.getId(), target);
            if (affected != 1) {
                throw new CodeErrorException("盘点修正并发冲突，事务已回滚：sku=" + item.getSku());
            }
            movementService.record(stocktake.getShopId(), stocktake.getWarehouseId(),
                    item.getSku(), row.getBatchNo(), before, target,
                    InventoryMovement.TYPE_STOCKTAKE_ADJUST,
                    "STOCKTAKE", stocktake.getStocktakeNo(), null,
                    "盘点修正（快照=" + item.getExpectedQty() + " 实盘=" + target + "）");
            adjusted++;
        }
        stocktake.setStatus(Stocktake.STATUS_APPLIED);
        stocktake.setAppliedAt(LocalDateTime.now());
        stocktakeMapper.updateById(stocktake);
        log.info("盘点修正落账 stocktakeNo={} 修正={} 未盘跳过={} 零差异={}",
                stocktake.getStocktakeNo(), adjusted, skippedUncounted, noDiff);
        return new ApplyResult(adjusted, skippedUncounted, noDiff);
    }

    /**
     * 取消（DRAFT/COUNTED）。
     */
    public Stocktake cancel(Long stocktakeId) {
        Stocktake stocktake = load(stocktakeId, "盘点单");
        if (Stocktake.STATUS_APPLIED.equals(stocktake.getStatus())) {
            throw new CodeErrorException("已落账（APPLIED）的盘点单不可取消");
        }
        stocktake.setStatus(Stocktake.STATUS_CANCELLED);
        stocktakeMapper.updateById(stocktake);
        log.info("盘点取消 stocktakeNo={}", stocktake.getStocktakeNo());
        return stocktake;
    }

    /**
     * 盘点单列表（按店铺，可选状态）。
     */
    public List<Stocktake> list(Long shopId, String status) {
        LogisticsShopGuard.requireShopAllowed(shopId, "盘点单");
        LambdaQueryWrapper<Stocktake> q = new LambdaQueryWrapper<>();
        q.eq(Stocktake::getShopId, shopId);
        if (StringUtils.hasText(status)) {
            q.eq(Stocktake::getStatus, status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        q.orderByDesc(Stocktake::getId);
        return stocktakeMapper.selectList(q);
    }

    /** 盘点单 + 明细。 */
    public StocktakeDetail detail(Long stocktakeId) {
        Stocktake stocktake = load(stocktakeId, "盘点单");
        List<StocktakeItem> items = stocktakeItemMapper.selectList(new LambdaQueryWrapper<StocktakeItem>()
                .eq(StocktakeItem::getStocktakeId, stocktakeId)
                .orderByAsc(StocktakeItem::getSku));
        return new StocktakeDetail(stocktake, items);
    }

    // ============ 内部 ============

    private Stocktake load(Long id, String what) {
        if (id == null) {
            throw new CodeErrorException(what + " ID 不能为空");
        }
        Stocktake stocktake = stocktakeMapper.selectById(id);
        if (stocktake == null) {
            throw new CodeErrorException(what + "不存在：id=" + id);
        }
        LogisticsShopGuard.requireShopAllowed(stocktake.getShopId(), what);
        return stocktake;
    }

    private WarehouseInventory findRow(Long warehouseId, Long shopId, String sku) {
        LambdaQueryWrapper<WarehouseInventory> q = new LambdaQueryWrapper<>();
        q.eq(WarehouseInventory::getWarehouseId, warehouseId)
                .eq(WarehouseInventory::getShopId, shopId)
                .eq(WarehouseInventory::getSku, sku);
        List<WarehouseInventory> rows = inventoryMapper.selectList(q);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String generateNo() {
        return "STK" + LocalDateTime.now().format(NO_FMT)
                + ThreadLocalRandom.current().nextInt(100, 1000);
    }

    /** 录入实盘的一行。 */
    public record CountLine(String sku, Integer countedQty) {
    }

    /** 修正统计。 */
    public record ApplyResult(int adjusted, int skippedUncounted, int noDiff) {
    }

    /** 盘点单 + 明细。 */
    public record StocktakeDetail(Stocktake stocktake, List<StocktakeItem> items) {
    }
}
