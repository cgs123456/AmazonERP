package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.mapper.WarehouseMapper;
import com.amz.model.Warehouse;
import com.amz.model.WarehouseInventory;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import static com.amz.service.impl.LogisticsShopGuard.requireShopAllowed;

/**
 * 海外仓服务实现。
 */
@Service
public class WarehouseServiceImpl implements WarehouseService {

    /** 与 DDL 的 location_code VARCHAR(50) 对齐：超长会被 MySQL 严格模式拒掉，报错比这里难读。 */
    static final int LOCATION_CODE_MAX_LENGTH = 50;

    @Autowired
    private WarehouseMapper warehouseMapper;

    @Autowired
    private WarehouseInventoryMapper inventoryMapper;

    @Override
    public Warehouse createWarehouse(Warehouse warehouse) {
        if (warehouse == null) {
            throw new CodeErrorException("仓库内容不能为空");
        }
        requireShopAllowed(warehouse.getShopId(), "仓库");
        if (warehouse.getStatus() == null) {
            warehouse.setStatus("ACTIVE");
        }
        if (warehouse.getWarehouseType() == null) {
            warehouse.setWarehouseType("THIRD_PARTY");
        }
        if (warehouse.getUsedCbm() == null) {
            warehouse.setUsedCbm(java.math.BigDecimal.ZERO);
        }
        warehouseMapper.insert(warehouse);
        return warehouse;
    }

    @Override
    public Warehouse updateWarehouse(Warehouse warehouse) {
        if (warehouse == null || warehouse.getId() == null) {
            throw new CodeErrorException("仓库 ID 不能为空");
        }
        Warehouse existing = warehouseMapper.selectById(warehouse.getId());
        if (existing == null || !UserContext.isShopAllowed(existing.getShopId())) {
            throw new CodeErrorException("仓库不存在或无权访问");
        }
        if (warehouse.getShopId() != null && !warehouse.getShopId().equals(existing.getShopId())) {
            throw new CodeErrorException("仓库归属店铺不可修改");
        }
        // 即使请求体省略 shopId，也不能让 ORM 的更新语义把仓库挂到别的店铺
        warehouse.setShopId(existing.getShopId());
        warehouseMapper.updateById(warehouse);
        return warehouse;
    }

    @Override
    public List<Warehouse> listWarehouses(Long shopId, String warehouseType) {
        requireShopAllowed(shopId, "仓库");
        LambdaQueryWrapper<Warehouse> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Warehouse::getShopId, shopId);
        if (warehouseType != null && !warehouseType.isBlank()) {
            wrapper.eq(Warehouse::getWarehouseType, warehouseType);
        }
        wrapper.orderByDesc(Warehouse::getId);
        return warehouseMapper.selectList(wrapper);
    }

    @Override
    public PageResult<WarehouseInventory> listInventory(Long warehouseId, String sku, Long shopId,
                                                        PageRequest page) {
        requireShopAllowed(shopId, "库存");
        if (page == null) {
            throw new CodeErrorException("分页参数不能为空");
        }
        LambdaQueryWrapper<WarehouseInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventory::getShopId, shopId);
        if (warehouseId != null) {
            wrapper.eq(WarehouseInventory::getWarehouseId, warehouseId);
        }
        if (sku != null && !sku.isBlank()) {
            wrapper.eq(WarehouseInventory::getSku, sku);
        }
        Long cursorId = page.cursorId();
        if (cursorId != null) {
            wrapper.lt(WarehouseInventory::getId, cursorId);
        }
        wrapper.orderByDesc(WarehouseInventory::getId)
                .last("LIMIT " + page.probeSize());
        return PageResult.of(inventoryMapper.selectList(wrapper), page.size(),
                item -> PageRequest.encodeCursor(item.getId()));
    }

    @Override
    public WarehouseInventory updateLocationCode(Long inventoryId, String locationCode) {
        WarehouseInventory inv = inventoryMapper.selectById(inventoryId);
        if (inv == null || !UserContext.isShopAllowed(inv.getShopId())) {
            throw new CodeErrorException("库存记录不存在或无权访问");
        }
        // locationCode 是 query 上的裸字符串：?locationCode= 能通过「参数必填」却把库位清空，
        // 超过 DDL 的 VARCHAR(50) 会在严格模式下变成 500。两者都在写库前挡掉。
        String trimmed = locationCode == null ? "" : locationCode.trim();
        if (trimmed.isEmpty()) {
            throw new CodeErrorException("库位码不能为空（要清空请走专门的解绑操作，本接口不做静默清除）");
        }
        if (trimmed.length() > LOCATION_CODE_MAX_LENGTH) {
            throw new CodeErrorException("库位码最长 " + LOCATION_CODE_MAX_LENGTH + " 字符，实际 " + trimmed.length());
        }
        inv.setLocationCode(trimmed);
        inventoryMapper.updateById(inv);
        return inv;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseInventory increaseInventory(Long warehouseId, Long shopId, String sku,
                                                Integer quantity, String batchNo, String locationCode) {
        requireShopAllowed(shopId, "库存");
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("入库数量必须大于 0");
        }
        // 同仓库 + 同 SKU + 同批次 视为同一条库存记录（便于批次追溯）；
        // shopId 必须进入查询条件，否则不同店铺的库存可能被错误合并到同一行。
        LambdaQueryWrapper<WarehouseInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventory::getWarehouseId, warehouseId)
                .eq(WarehouseInventory::getShopId, shopId)
                .eq(WarehouseInventory::getSku, sku)
                .eq(batchNo != null, WarehouseInventory::getBatchNo, batchNo);
        List<WarehouseInventory> existing = inventoryMapper.selectList(wrapper);
        if (existing.size() > 1) {
            // §S33.105：查了 (warehouse_id, shop_id, sku, batch_no) 却回来多行，只可能是
            // 历史脏数据或并发双插。旧实现取 get(0) 后只增加那一行，其余行的库存永远不涨
            // ——账实不符且无任何信号。这里显式失败让人去修数据，不猜哪一行是对的。
            // 不靠唯一键兜底：MySQL 唯一键不约束 NULL，batch_no 为 NULL 时
            // (warehouse_id, shop_id, sku, NULL) 可以合法存在多行，加唯一键拦不住这一种。
            throw new IllegalStateException("库存记录不唯一：warehouseId=" + warehouseId
                    + " shopId=" + shopId + " sku=" + sku + " batchNo=" + batchNo
                    + " 命中 " + existing.size() + " 行，请先清理重复库存记录");
        }
        if (existing.isEmpty()) {
            WarehouseInventory inv = new WarehouseInventory();
            inv.setWarehouseId(warehouseId);
            inv.setShopId(shopId);
            inv.setSku(sku);
            inv.setQuantity(quantity);
            inv.setReservedQuantity(0);
            inv.setInboundQuantity(0);
            inv.setBatchNo(batchNo);
            inv.setLocationCode(locationCode);
            inventoryMapper.insert(inv);
            return inv;
        }
        WarehouseInventory inv = existing.get(0);
        // 原子增加库存，避免「读-改-写」并发问题（高并发下多个入库同时读到旧值会互相覆盖）
        inventoryMapper.increaseQuantityAtomic(inv.getId(), quantity);
        // 重新查询最新库存（包含原子增加后的 quantity），再按需更新 locationCode，
        // 避免直接 updateById(existing) 覆盖刚原子更新的 quantity
        WarehouseInventory latest = inventoryMapper.selectById(inv.getId());
        if (latest != null && locationCode != null) {
            latest.setLocationCode(locationCode);
            inventoryMapper.updateById(latest);
        }
        return latest != null ? latest : inv;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseInventory decreaseInventory(Long warehouseId, String sku, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("出库数量必须大于 0");
        }
        LambdaQueryWrapper<WarehouseInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventory::getWarehouseId, warehouseId)
                .eq(WarehouseInventory::getSku, sku);
        List<WarehouseInventory> list = inventoryMapper.selectList(wrapper);
        if (list.isEmpty()) {
            throw new IllegalStateException("库存不足：warehouseId=" + warehouseId + " sku=" + sku);
        }
        List<WarehouseInventory> usable = list.stream()
                .filter(item -> UserContext.isShopAllowed(item.getShopId()))
                .collect(Collectors.toList());
        if (usable.isEmpty()) {
            throw new CodeErrorException("库存记录不存在或无权访问");
        }
        return consumeAcrossBatches(usable, quantity,
                () -> "库存不足：warehouseId=" + warehouseId + " sku=" + sku + " need=" + quantity);
    }

    @Override
    public WarehouseInventory decreaseInventory(Long warehouseId, Long shopId, String sku, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("出库数量必须大于 0");
        }
        requireShopAllowed(shopId, "库存");
        LambdaQueryWrapper<WarehouseInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventory::getWarehouseId, warehouseId)
                .eq(WarehouseInventory::getShopId, shopId)
                .eq(WarehouseInventory::getSku, sku);
        List<WarehouseInventory> list = inventoryMapper.selectList(wrapper);
        if (list.isEmpty()) {
            throw new CodeErrorException("库存不足：warehouseId=" + warehouseId
                    + " shopId=" + shopId + " sku=" + sku);
        }
        return consumeAcrossBatches(list, quantity,
                () -> "库存不足：warehouseId=" + warehouseId + " shopId=" + shopId
                        + " sku=" + sku + " need=" + quantity);
    }

    /**
     * 按 FIFO 逐批扣减，不足时才失败（§S33.107）。
     *
     * <p>旧实现 {@code list.get(0)} 只试一行：单批可用量不够就直接报「库存不足」，
     * 哪怕多批合计远超需求量——出库和调拨都会被这个假失败挡住。
     *
     * <p><b>排序依据</b>：{@code id ASC}。实体未映射 {@code create_time}（表里存在），
     * 自增主键与入库先后单调一致，用它作为 FIFO 的代理排序；不改实体与 DDL。
     *
     * <p><b>刻意不做效期优先（FEFO）</b>：表里有 {@code expire_date}，
     * 食品/电池等品类通常要求先出近效期，但那是另一种业务规则，
     * 与本次批准的 FIFO 不同，不能在同一次改动里擅自升级——记在注释里待定。
     *
     * <p>原子性：单次扣减走 {@code decreaseQuantityAtomic} 的行级条件更新（可用量够才扣）；
     * 分批累加的完整性由外层 {@code @Transactional(rollbackFor = Exception.class)} 保证，
     * 任一批失败抛异常即整体回滚，不会留下「扣了一半」的残局。
     */
    private WarehouseInventory consumeAcrossBatches(List<WarehouseInventory> candidates,
                                                    Integer quantity,
                                                    java.util.function.Supplier<String> shortageMessage) {
        List<WarehouseInventory> ordered = candidates.stream()
                .filter(item -> item != null && item.getId() != null)
                .sorted(Comparator.comparing(WarehouseInventory::getId))
                .collect(Collectors.toList());

        int remaining = quantity;
        WarehouseInventory last = null;
        for (WarehouseInventory batch : ordered) {
            if (remaining <= 0) {
                break;
            }
            int affected = inventoryMapper.decreaseQuantityAtomic(batch.getId(), remaining);
            if (affected > 0) {
                remaining = 0;
                last = batch;
                break;
            }
            // 这一批可用量不够整笔，看它能出多少：能出多少先出多少，继续下一批
            Integer available = batch.getAvailableQuantity();
            int usable = available == null ? 0 : Math.max(0, Math.min(available, remaining));
            if (usable > 0) {
                if (inventoryMapper.decreaseQuantityAtomic(batch.getId(), usable) > 0) {
                    remaining -= usable;
                    last = batch;
                }
            }
        }
        if (remaining > 0 || last == null) {
            // 全部批次合计仍不足（或没有可用批次）：抛异常回滚上面已扣的部分
            throw new IllegalStateException(shortageMessage.get());
        }
        return inventoryMapper.selectById(last.getId());
    }

    @Override
    public int currentQuantity(Long warehouseId, Long shopId, String sku) {
        LambdaQueryWrapper<WarehouseInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventory::getWarehouseId, warehouseId)
                .eq(WarehouseInventory::getShopId, shopId)
                .eq(WarehouseInventory::getSku, sku);
        List<WarehouseInventory> list = inventoryMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return 0;
        }
        Integer qty = list.get(0).getQuantity();
        return qty == null ? 0 : qty;
    }

}
