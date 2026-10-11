package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.InventoryMovementMapper;
import com.amz.mapper.ReturnOrderMapper;
import com.amz.mapper.WarehouseInventoryMapper;
import com.amz.model.InventoryMovement;
import com.amz.model.ReturnOrder;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 退货单服务（买家退货入仓）。
 * <p>
 * <b>存在动因</b>：全仓没有任何退货单——{@code amz_inbound_order.source} 没有 RETURN，
 * 出库单的 RETURN 是「退货出库」（方向相反）。退款只走财务域（结算报表 → 凭证），
 * 「货有没有退回来、退回多少件、良品还是残次」没有任何落点，于是 COGS 不会因退货冲减、
 * 库存不会回补。本服务补上「货的那半条链」。
 * <p>
 * <b>状态机白名单</b>（与采购计划/调拨单同一约定）：跳过前置状态一律拒绝，
 * 避免「没收货就结案」把账做成假的。重复操作幂等。
 * <p>
 * <b>收货即回补库存并写流水</b>：只有 GOOD（良品）才回补可用库存；
 * DEFECTIVE/SCRAP 只留流水（数量变动为 0 或仅记录处置），不进可用库存——
 * 把残次品算进可售库存会让补货建议虚高。
 */
@Slf4j
@Service
public class ReturnOrderService {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_RECEIVING = "RECEIVING";
    static final String STATUS_RECEIVED = "RECEIVED";
    static final String STATUS_CLOSED = "CLOSED";
    static final String STATUS_CANCELLED = "CANCELLED";

    static final String DISPOSITION_GOOD = "GOOD";
    static final String DISPOSITION_DEFECTIVE = "DEFECTIVE";
    static final String DISPOSITION_SCRAP = "SCRAP";

    private static final Set<String> DISPOSITIONS =
            Set.of(DISPOSITION_GOOD, DISPOSITION_DEFECTIVE, DISPOSITION_SCRAP);
    private static final Set<String> SOURCES =
            Set.of("AMAZON_FBA", "AMAZON_FBM", "MANUAL");

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Autowired
    private ReturnOrderMapper returnOrderMapper;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private InventoryMovementService movementService;

    @Autowired
    private WarehouseInventoryMapper inventoryMapper;

    /**
     * 创建退货单（PENDING）。
     */
    public ReturnOrder create(ReturnOrder order) {
        if (order == null) {
            throw new CodeErrorException("退货单内容不能为空");
        }
        LogisticsShopGuard.requireShopAllowed(order.getShopId(), "退货单");
        if (!StringUtils.hasText(order.getSku())) {
            throw new CodeErrorException("SKU 不能为空");
        }
        if (order.getQuantity() == null || order.getQuantity() <= 0) {
            throw new CodeErrorException("退货数量必须大于 0");
        }
        String source = order.getSource() == null ? "MANUAL"
                : order.getSource().trim().toUpperCase(java.util.Locale.ROOT);
        if (!SOURCES.contains(source)) {
            throw new CodeErrorException("退货来源只支持 AMAZON_FBA/AMAZON_FBM/MANUAL，实际「" + order.getSource() + "」");
        }
        order.setId(null);
        order.setSource(source);
        order.setStatus(STATUS_PENDING);
        if (!StringUtils.hasText(order.getReturnNo())) {
            order.setReturnNo(generateReturnNo());
        }
        order.setOperatorId(UserContext.getUserId() == null ? null : UserContext.getUserId().longValue());
        order.setReceivedAt(null);
        order.setClosedAt(null);
        returnOrderMapper.insert(order);
        log.info("创建退货单 returnNo={} shopId={} sku={} qty={} source={}",
                order.getReturnNo(), order.getShopId(), order.getSku(), order.getQuantity(), source);
        return order;
    }

    /**
     * Amazon 退货报表导入：按外部幂等键 upsert，重复导入不产生第二行。
     * <p>
     * <b>与手工建单的区别</b>：手工建单没有外部标识（{@code externalKey=null}），
     * 每次都是新单；报表导入必须能识别「这行导过了」——同一笔退货重复导入
     * 会重复回补库存。幂等键 {@code externalKey} 由解析器按
     * {@code 来源|return-date|order-id|sku} 生成，落库层拼上 shopId 后写库，
     * 唯一索引 {@code uk_external_key} 兜底并发。
     * <p>
     * <b>重复时不更新</b>：同一笔退货的后续报表行可能带着更新的 disposition/status，
     * 但本地退货单一旦收货就进入了状态机，被报表静默改写会让「谁在什么时候收的货」
     * 失去意义。因此重复即跳过并计数，由运营按需手工调整。
     *
     * @param order 已填好 shopId/sku/quantity/disposition/source/externalKey 的退货单
     * @return true=新导入，false=已存在（跳过）
     */
    public boolean importAmazonReturn(ReturnOrder order) {
        if (order == null) {
            throw new CodeErrorException("退货单内容不能为空");
        }
        LogisticsShopGuard.requireShopAllowed(order.getShopId(), "退货单");
        if (!StringUtils.hasText(order.getSku())) {
            throw new CodeErrorException("SKU 不能为空");
        }
        if (order.getQuantity() == null || order.getQuantity() <= 0) {
            throw new CodeErrorException("退货数量必须大于 0");
        }
        if (!StringUtils.hasText(order.getExternalKey())) {
            throw new CodeErrorException("Amazon 退货导入必须带外部幂等键（externalKey）");
        }
        String source = order.getSource() == null ? null
                : order.getSource().trim().toUpperCase(java.util.Locale.ROOT);
        if (source == null || !SOURCES.contains(source)) {
            throw new CodeErrorException("退货来源只支持 AMAZON_FBA/AMAZON_FBM/MANUAL，实际「" + order.getSource() + "」");
        }
        // 幂等键拼上店铺：不同店铺可能有相同的 Amazon 订单号形态（理论上不会，但把店铺
        // 纳入键能让唯一约束在跨店场景下仍然精确，不需要依赖「订单号全局唯一」这个假设）
        String scopedKey = order.getShopId() + "|" + source + "|" + order.getExternalKey();
        order.setExternalKey(scopedKey);

        // 先查后写：命中即跳过（避免依赖唯一键异常做常规控制流）
        LambdaQueryWrapper<ReturnOrder> q = new LambdaQueryWrapper<>();
        q.eq(ReturnOrder::getExternalKey, scopedKey);
        if (returnOrderMapper.selectOne(q) != null) {
            log.info("Amazon 退货已存在，跳过 returnKey={} order={} sku={}",
                    scopedKey, order.getAmazonOrderId(), order.getSku());
            return false;
        }

        order.setId(null);
        order.setSource(source);
        order.setStatus(STATUS_PENDING);
        if (!StringUtils.hasText(order.getReturnNo())) {
            order.setReturnNo(generateReturnNo());
        }
        order.setOperatorId(UserContext.getUserId() == null ? null : UserContext.getUserId().longValue());
        order.setReceivedAt(null);
        order.setClosedAt(null);
        try {
            returnOrderMapper.insert(order);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发导入：唯一键兜底，视为已存在（不重复回补）
            log.info("Amazon 退货并发重复导入，唯一键拦截 returnKey={}", scopedKey);
            return false;
        }
        log.info("导入 Amazon 退货 returnNo={} shopId={} order={} sku={} qty={} disposition={}",
                order.getReturnNo(), order.getShopId(), order.getAmazonOrderId(), order.getSku(),
                order.getQuantity(), order.getDisposition());
        return true;
    }

    /**
     * 收货：确定处置，良品回补库存并写流水。
     *
     * @param returnId    退货单 ID
     * @param warehouseId 收货仓库（必填）
     * @param disposition GOOD / DEFECTIVE / SCRAP
     */
    @Transactional(rollbackFor = Exception.class)
    public ReturnOrder receive(Long returnId, Long warehouseId, String disposition) {
        ReturnOrder order = loadForDisposition(returnId, "退货单");
        if (warehouseId == null) {
            throw new CodeErrorException("收货仓库不能为空");
        }
        String disp = disposition == null ? null : disposition.trim().toUpperCase(java.util.Locale.ROOT);
        if (disp == null || !DISPOSITIONS.contains(disp)) {
            throw new CodeErrorException("处置只支持 GOOD/DEFECTIVE/SCRAP，实际「" + disposition + "」");
        }
        // 状态白名单：只有 PENDING 可收货（RECEIVING 是中间态，本实现一步到位）
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new CodeErrorException("退货单当前状态为 " + order.getStatus() + "，不可收货");
        }

        order.setWarehouseId(warehouseId);
        order.setDisposition(disp);
        order.setStatus(STATUS_RECEIVED);
        order.setReceivedAt(LocalDateTime.now());
        returnOrderMapper.updateById(order);

        // 只有良品回补可用库存；残次/报废只留流水，不进可售库存
        if (DISPOSITION_GOOD.equals(disp)) {
            int before = currentQty(warehouseId, order.getShopId(), order.getSku());
            WarehouseInventory inv = warehouseService.increaseInventory(
                    warehouseId, order.getShopId(), order.getSku(),
                    order.getQuantity(), order.getReturnNo(), null);
            int after = inv != null && inv.getQuantity() != null ? inv.getQuantity() : before + order.getQuantity();
            movementService.record(order.getShopId(), warehouseId, order.getSku(), order.getReturnNo(),
                    before, after, InventoryMovement.TYPE_RETURN_INBOUND,
                    "RETURN_ORDER", order.getReturnNo(), null,
                    "退货良品回补（处置=" + disp + "）");
        } else {
            // 残次/报废：不动物理库存，但留一条 0 变更流水，说明「货收到了、未入可售」
            int current = currentQty(warehouseId, order.getShopId(), order.getSku());
            movementService.record(order.getShopId(), warehouseId, order.getSku(), order.getReturnNo(),
                    current, current, InventoryMovement.TYPE_RETURN_INBOUND,
                    "RETURN_ORDER", order.getReturnNo(), null,
                    "退货收货但未回补（处置=" + disp + "，不计入可售库存）");
        }
        log.info("退货收货 returnNo={} disposition={} warehouseId={} qty={}",
                order.getReturnNo(), disp, warehouseId, order.getQuantity());
        return order;
    }

    /**
     * 结案：RECEIVED → CLOSED。
     */
    public ReturnOrder close(Long returnId) {
        ReturnOrder order = loadForDisposition(returnId, "退货单");
        if (!STATUS_RECEIVED.equals(order.getStatus())) {
            throw new CodeErrorException("只有已收货（RECEIVED）的退货单可结案，当前 " + order.getStatus());
        }
        order.setStatus(STATUS_CLOSED);
        order.setClosedAt(LocalDateTime.now());
        returnOrderMapper.updateById(order);
        log.info("退货结案 returnNo={}", order.getReturnNo());
        return order;
    }

    /**
     * 取消：仅 PENDING 可取消。
     */
    public ReturnOrder cancel(Long returnId) {
        ReturnOrder order = loadForDisposition(returnId, "退货单");
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new CodeErrorException("只有待收货（PENDING）的退货单可取消，当前 " + order.getStatus());
        }
        order.setStatus(STATUS_CANCELLED);
        returnOrderMapper.updateById(order);
        log.info("退货取消 returnNo={}", order.getReturnNo());
        return order;
    }

    /**
     * 退货单列表（按店铺，可选状态过滤）。
     */
    public List<ReturnOrder> list(Long shopId, String status) {
        LogisticsShopGuard.requireShopAllowed(shopId, "退货单");
        LambdaQueryWrapper<ReturnOrder> q = new LambdaQueryWrapper<>();
        q.eq(ReturnOrder::getShopId, shopId);
        if (StringUtils.hasText(status)) {
            q.eq(ReturnOrder::getStatus, status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        q.orderByDesc(ReturnOrder::getId);
        return returnOrderMapper.selectList(q);
    }

    /**
     * 已确认收货的良品退货件数合计（供财务侧冲减 COGS）。
     * <p>
     * 只统计 {@code status=RECEIVED/CLOSED} 且 {@code disposition=GOOD} 的单：
     * 残次/报废虽收到了货，但不可再售，不能用来冲减销货成本（否则等于把损耗
     * 当成可售库存，利润虚高）。
     * <p>
     * 这是 finance↔logistics 之间此前完全缺失的那条通道（两域无任何 Feign/MQ），
     * 退款闭环的「货」那一半要落到利润上，就必须有它。
     */
    public int returnedGoodUnits(Long shopId, String sku) {
        LogisticsShopGuard.requireShopAllowed(shopId, "退货单");
        if (!StringUtils.hasText(sku)) {
            return 0;
        }
        LambdaQueryWrapper<ReturnOrder> q = new LambdaQueryWrapper<>();
        q.eq(ReturnOrder::getShopId, shopId)
                .eq(ReturnOrder::getSku, sku)
                .eq(ReturnOrder::getDisposition, DISPOSITION_GOOD)
                .in(ReturnOrder::getStatus, List.of(STATUS_RECEIVED, STATUS_CLOSED));
        List<ReturnOrder> rows = returnOrderMapper.selectList(q);
        int total = 0;
        for (ReturnOrder row : rows) {
            total += row.getQuantity() == null ? 0 : row.getQuantity();
        }
        return total;
    }

    // ============ 内部 ============

    private ReturnOrder loadForDisposition(Long returnId, String what) {
        if (returnId == null) {
            throw new CodeErrorException(what + " ID 不能为空");
        }
        ReturnOrder order = returnOrderMapper.selectById(returnId);
        if (order == null) {
            throw new CodeErrorException(what + "不存在：id=" + returnId);
        }
        LogisticsShopGuard.requireShopAllowed(order.getShopId(), what);
        return order;
    }

    /** 读当前库存量（无记录按 0），用于流水的 before/after。 */
    private int currentQty(Long warehouseId, Long shopId, String sku) {
        LambdaQueryWrapper<WarehouseInventory> q = new LambdaQueryWrapper<>();
        q.eq(WarehouseInventory::getWarehouseId, warehouseId)
                .eq(WarehouseInventory::getShopId, shopId)
                .eq(WarehouseInventory::getSku, sku);
        List<WarehouseInventory> rows = inventoryMapper.selectList(q);
        if (rows.isEmpty()) {
            return 0;
        }
        Integer qty = rows.get(0).getQuantity();
        return qty == null ? 0 : qty;
    }

    private String generateReturnNo() {
        return "RET" + LocalDateTime.now().format(NO_FMT)
                + ThreadLocalRandom.current().nextInt(100, 1000);
    }
}
