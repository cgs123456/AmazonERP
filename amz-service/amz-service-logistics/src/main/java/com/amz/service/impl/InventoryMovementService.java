package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.InventoryMovementMapper;
import com.amz.model.InventoryMovement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 库存流水记录器（本模块唯一写入点）。
 * <p>
 * 存在动因：全仓此前没有任何库存移动账，库存变更不可追溯。本类把「写流水」收敛成一处，
 * 让各变更点只负责给出 before/after，不各自拼流水字段（否则字段口径必然漂移）。
 * <p>
 * <b>只增不改</b>：只提供 record，不提供 update/delete。流水是事实记录，
 * 改写历史等于伪造账目。
 * <p>
 * <b>记录失败不阻断业务</b>：与操作审计同一条取舍——库存已经变了，
 * 因为流水写不进去而让业务回滚，是把「账不全」升级成「货动不了」。
 * 失败记 warn 并计数，由运维按日志补记。这与「变更必须留痕」的目标不冲突：
 * 目标是可追溯，不是「无流水就禁止变更」。
 */
@Slf4j
@Service
public class InventoryMovementService {

    @Autowired
    private InventoryMovementMapper movementMapper;

    /** 累计写流水失败次数，供观测与测试断言。 */
    private final java.util.concurrent.atomic.AtomicLong failureCount =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 记录一次库存变更。
     *
     * @param shopId        店铺
     * @param warehouseId   仓库（FIFO 批次变更可为 null）
     * @param sku           SKU
     * @param batchNo       批次号（可空）
     * @param qtyBefore     变更前数量
     * @param qtyAfter      变更后数量
     * @param movementType  业务动作（见 {@link InventoryMovement} 常量）
     * @param sourceType    来源单据类型
     * @param sourceNo      来源单号
     * @param unitCost      单位成本（可空）
     * @param remark        备注（可空）
     * @return 是否写成功
     */
    public boolean record(Long shopId, Long warehouseId, String sku, String batchNo,
                          Integer qtyBefore, Integer qtyAfter, String movementType,
                          String sourceType, String sourceNo, BigDecimal unitCost, String remark) {
        if (shopId == null || sku == null || movementType == null) {
            log.warn("库存流水缺少必填字段，跳过：shopId={} sku={} movementType={}",
                    shopId, sku, movementType);
            return false;
        }
        if (qtyBefore == null || qtyAfter == null) {
            // before/after 是发现「并发下 before 已变」的关键，缺失时宁可记 null 也不猜
            log.warn("库存流水缺少 before/after，仍记录但值为 null：shopId={} sku={} type={}",
                    shopId, sku, movementType);
        }
        InventoryMovement movement = new InventoryMovement();
        movement.setShopId(shopId);
        movement.setWarehouseId(warehouseId);
        movement.setSku(sku);
        movement.setBatchNo(batchNo);
        movement.setQtyBefore(qtyBefore);
        movement.setQtyAfter(qtyAfter);
        // delta 由 before/after 推导而不是由调用方传：两个来源给同一个量必然会有对不上的一天
        movement.setQtyDelta(qtyBefore != null && qtyAfter != null ? qtyAfter - qtyBefore : null);
        movement.setMovementType(movementType);
        movement.setSourceType(sourceType);
        movement.setSourceNo(sourceNo);
        movement.setUnitCost(unitCost);
        movement.setOperatorId(UserContext.getUserId() == null ? null : UserContext.getUserId().longValue());
        movement.setRemark(remark);
        try {
            movementMapper.insert(movement);
            return true;
        } catch (Exception e) {
            long n = failureCount.incrementAndGet();
            if (n <= 3 || n % 100 == 0) {
                log.warn("库存流水写入失败（第 {} 条，业务不受影响）shopId={} sku={} type={}。原因: {}",
                        n, shopId, sku, movementType, e.getMessage());
            }
            return false;
        }
    }

    /** 便捷方法：数量净增（入库/回补）。 */
    public boolean recordIncrease(Long shopId, Long warehouseId, String sku, String batchNo,
                                  Integer qtyBefore, Integer qtyAfter, String movementType,
                                  String sourceType, String sourceNo, BigDecimal unitCost, String remark) {
        return record(shopId, warehouseId, sku, batchNo, qtyBefore, qtyAfter, movementType,
                sourceType, sourceNo, unitCost, remark);
    }

    public long failureCount() {
        return failureCount.get();
    }
}
