package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.util.BizNoGenerator;

import com.amz.dto.BatchCostSummary;
import com.amz.dto.ReceiptShortage;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.FbaShipmentItemMapper;
import com.amz.mapper.FbaShipmentMapper;
import com.amz.mapper.InventoryBatchMapper;
import com.amz.model.FbaShipment;
import com.amz.model.FbaShipmentItem;
import com.amz.model.InventoryBatch;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.FbaShipmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FBA 货件管理服务实现。
 * <p>
 * 覆盖 Send to Amazon 全流程 + 头程费用分摊 + FIFO 批次管理。
 */
@Slf4j
@Service
public class FbaShipmentServiceImpl implements FbaShipmentService {

    /** 入库短收：每页明细行数与货件 ID 扫描上限（扫描上限命中时结果不是全量，只告警不静默） */
    private static final int RECEIPT_SHORTAGE_PAGE_SIZE = 100;
    private static final int RECEIPT_SHORTAGE_SHIPMENT_SCAN = 2000;

    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 费用分摊/签收属于整单事务，必须读取全量明细。
     * 超过该上限直接失败，禁止只处理前几页后静默写坏成本与库存。
     */
    private static final int MAX_SHIPMENT_ITEMS_FOR_MUTATION = 10_000;

    private static final int SHIPMENT_ITEM_PAGE_SIZE = PageRequest.MAX_SIZE;

    /** FIFO 出库必须读取全量 ACTIVE 批次；超过上限直接失败，禁止静默漏扣。 */
    private static final int MAX_BATCHES_FOR_FIFO = 10_000;

    private static final int BATCH_PAGE_SIZE = PageRequest.MAX_SIZE;

    @Autowired
    private FbaShipmentMapper fbaShipmentMapper;

    @Autowired
    private FbaShipmentItemMapper fbaShipmentItemMapper;

    @Autowired
    private InventoryBatchMapper inventoryBatchMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FbaShipment createShipment(FbaShipment shipment) {
        if (shipment.getShopId() == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        // 多租户越权防护：创建入口仅携带 Body、无 shopId 参数可被 ShopScoped 切面拦截，
        // 此处显式校验目标店铺归属（与 ProcurementServiceImpl#mustGetAuthorized 同模式）
        if (!UserContext.isShopAllowed(shipment.getShopId())) {
            throw new CodeErrorException("无权操作该店铺的货件：shopId=" + shipment.getShopId());
        }
        shipment.setShipmentNo(BizNoGenerator.next("FBA"));
        if (shipment.getStatus() == null) {
            shipment.setStatus("CREATED");
        }
        if (shipment.getBoxCount() == null) shipment.setBoxCount(0);
        if (shipment.getTotalWeight() == null) shipment.setTotalWeight(BigDecimal.ZERO);
        if (shipment.getTotalVolume() == null) shipment.setTotalVolume(BigDecimal.ZERO);
        if (shipment.getFreightCost() == null) shipment.setFreightCost(BigDecimal.ZERO);
        if (shipment.getCustomsCost() == null) shipment.setCustomsCost(BigDecimal.ZERO);
        if (shipment.getTaxCost() == null) shipment.setTaxCost(BigDecimal.ZERO);
        if (shipment.getOtherCost() == null) shipment.setOtherCost(BigDecimal.ZERO);
        if (shipment.getTotalCost() == null) shipment.setTotalCost(BigDecimal.ZERO);
        fbaShipmentMapper.insert(shipment);
        return shipment;
    }

    @Override
    public FbaShipment updateShipment(FbaShipment shipment) {
        if (shipment.getId() == null) {
            throw new AttrIsNullException("货件ID不能为空");
        }
        // 越权防护：按 ID 更新前先校验既有记录的店铺归属，并以既有 shopId 覆盖请求体，
        // 防止把记录搬到其他店铺（updateById 全字段更新会连带写入请求体 shopId）
        FbaShipment existed = mustGetAuthorized(shipment.getId());
        shipment.setShopId(existed.getShopId());
        fbaShipmentMapper.updateById(shipment);
        return shipment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FbaShipmentItem addShipmentItem(FbaShipmentItem item) {
        if (item.getFbaShipmentId() == null || item.getSku() == null || item.getQuantity() == null) {
            throw new AttrIsNullException("货件ID、SKU和数量不能为空");
        }
        // 越权防护：明细挂靠的父货件必须属于授权店铺
        mustGetAuthorized(item.getFbaShipmentId());
        if (item.getReceivedQuantity() == null) item.setReceivedQuantity(0);
        if (item.getFreightAllocation() == null) item.setFreightAllocation(BigDecimal.ZERO);
        if (item.getCustomsAllocation() == null) item.setCustomsAllocation(BigDecimal.ZERO);
        if (item.getTotalCost() == null) item.setTotalCost(BigDecimal.ZERO);
        fbaShipmentItemMapper.insert(item);
        return item;
    }

    @Override
    public PageResult<FbaShipmentItem> listShipmentItems(Long shipmentId, PageRequest page) {
        // 越权防护：先校验父货件归属（兼存在性校验）
        mustGetAuthorized(shipmentId);
        if (page == null) {
            throw new CodeErrorException("分页参数不能为空");
        }
        return queryShipmentItemsPage(shipmentId, page, false);
    }

    /**
     * 查询一页货件明细。调用方必须先完成父货件归属校验。
     */
    private PageResult<FbaShipmentItem> queryShipmentItemsPage(Long shipmentId, PageRequest page,
                                                                 boolean forUpdate) {
        LambdaQueryWrapper<FbaShipmentItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FbaShipmentItem::getFbaShipmentId, shipmentId);
        Long cursorId = page.cursorId();
        if (cursorId != null) {
            wrapper.lt(FbaShipmentItem::getId, cursorId);
        }
        wrapper.orderByDesc(FbaShipmentItem::getId)
                .last("LIMIT " + page.probeSize() + (forUpdate ? " FOR UPDATE" : ""));
        return PageResult.of(fbaShipmentItemMapper.selectList(wrapper), page.size(),
                item -> PageRequest.encodeCursor(item.getId()));
    }

    @Override
    public PageResult<ReceiptShortage> listReceiptShortages(Long shopId, PageRequest page) {
        if (shopId == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            throw new CodeErrorException("无权查询该店铺的入库短收：shopId=" + shopId);
        }
        PageRequest req = page == null ? PageRequest.first(RECEIPT_SHORTAGE_PAGE_SIZE) : page;

        // 明细表没有 shop_id，只能先按店铺扫出货件 ID 再用 in 过滤明细；
        // 扫描有硬上限，命中就告警——「扫到上限」和「这家店没有短收」是两个结论。
        List<FbaShipment> shipments = fbaShipmentMapper.selectList(new LambdaQueryWrapper<FbaShipment>()
                .select(FbaShipment::getId, FbaShipment::getShipmentNo,
                        FbaShipment::getFbaShipmentId, FbaShipment::getStatus)
                .eq(FbaShipment::getShopId, shopId)
                .isNotNull(FbaShipment::getFbaShipmentId)
                .orderByAsc(FbaShipment::getId)
                .last("LIMIT " + RECEIPT_SHORTAGE_SHIPMENT_SCAN));
        if (shipments.size() >= RECEIPT_SHORTAGE_SHIPMENT_SCAN) {
            log.warn("入库短收扫描命中货件上限：shopId={} scan={}，之后的货件未扫描，结果不是全量",
                    shopId, RECEIPT_SHORTAGE_SHIPMENT_SCAN);
        }
        if (shipments.isEmpty()) {
            return PageResult.of(List.<ReceiptShortage>of(), req.size(),
                    row -> PageRequest.encodeCursor(row.getItemId()));
        }
        Map<Long, FbaShipment> shipmentById = new LinkedHashMap<>();
        for (FbaShipment s : shipments) {
            shipmentById.put(s.getId(), s);
        }

        LambdaQueryWrapper<FbaShipmentItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(FbaShipmentItem::getFbaShipmentId, shipmentById.keySet())
                .isNotNull(FbaShipmentItem::getReceivedQuantity)
                .apply("received_quantity < quantity");
        Long cursorId = req.cursorId();
        if (cursorId != null) {
            wrapper.lt(FbaShipmentItem::getId, cursorId);
        }
        wrapper.orderByDesc(FbaShipmentItem::getId)
                .last("LIMIT " + req.probeSize());

        List<ReceiptShortage> rows = new ArrayList<>();
        for (FbaShipmentItem item : fbaShipmentItemMapper.selectList(wrapper)) {
            FbaShipment s = shipmentById.get(item.getFbaShipmentId());
            if (s == null || item.getQuantity() == null || item.getReceivedQuantity() == null) {
                continue;
            }
            ReceiptShortage row = new ReceiptShortage();
            row.setItemId(item.getId());
            row.setShipmentId(s.getId());
            row.setShipmentNo(s.getShipmentNo());
            row.setFbaShipmentId(s.getFbaShipmentId());
            row.setSku(item.getSku());
            row.setAsin(item.getAsin());
            row.setExpectedQty(item.getQuantity());
            row.setReceivedQty(item.getReceivedQuantity());
            row.setShortUnits(item.getQuantity() - item.getReceivedQuantity());
            row.setUnitCost(item.getUnitCost());
            row.setTotalCost(item.getTotalCost());
            row.setShipmentStatus(s.getStatus());
            rows.add(row);
        }
        return PageResult.of(rows, req.size(), row -> PageRequest.encodeCursor(row.getItemId()));
    }

    /**
     * 费用分摊/签收读取整单全量明细。
     * <p>只读第一页会漏摊后续 SKU，甚至把部分签收误判为整单完成；
     * 超过硬上限则 fail-closed，提示拆分货件，而不是继续产生错误结果。</p>
     */
    private List<FbaShipmentItem> loadAllShipmentItemsForMutation(FbaShipment authorizedShipment) {
        List<FbaShipmentItem> all = new ArrayList<>();
        PageRequest page = PageRequest.first(SHIPMENT_ITEM_PAGE_SIZE);
        while (true) {
            PageResult<FbaShipmentItem> current =
                    queryShipmentItemsPage(authorizedShipment.getId(), page, true);
            all.addAll(current.items());
            if (all.size() > MAX_SHIPMENT_ITEMS_FOR_MUTATION) {
                throw new CodeErrorException("货件明细超过安全上限 "
                        + MAX_SHIPMENT_ITEMS_FOR_MUTATION + " 条，无法安全完成整单分摊/签收；请拆分货件后重试");
            }
            if (!current.hasMore()) {
                return all;
            }
            page = PageRequest.of(SHIPMENT_ITEM_PAGE_SIZE, current.nextCursor());
        }
    }

    @Override
    public PageResult<FbaShipment> listShipments(Long shopId, String status, PageRequest page) {
        if (shopId == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        if (page == null) {
            throw new CodeErrorException("分页参数不能为空");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            throw new CodeErrorException("无权查询该店铺的货件：shopId=" + shopId);
        }
        LambdaQueryWrapper<FbaShipment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FbaShipment::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(FbaShipment::getStatus, status);
        }
        Long cursorId = page.cursorId();
        if (cursorId != null) {
            wrapper.lt(FbaShipment::getId, cursorId);
        }
        wrapper.orderByDesc(FbaShipment::getId)
                .last("LIMIT " + page.probeSize());
        return PageResult.of(fbaShipmentMapper.selectList(wrapper), page.size(),
                item -> PageRequest.encodeCursor(item.getId()));
    }

    @Override
    public FbaShipment getShipment(Long id) {
        FbaShipment shipment = fbaShipmentMapper.selectById(id);
        if (shipment == null) {
            throw new AttrIsNullException("货件不存在：id=" + id);
        }
        if (!UserContext.isShopAllowed(shipment.getShopId())) {
            throw new CodeErrorException("无权操作该店铺的货件：id=" + id);
        }
        return shipment;
    }

    /**
     * 加载货件并做多租户越权校验（与 ProcurementServiceImpl#mustGetAuthorized 同模式）。
     * <p>
     * status/ship/allocate/receive 等端点仅携带货件 ID、无 shopId 参数，
     * ShopScoped 切面无法按参数名拦截，故统一收敛到 getShipment 的归属校验。
     */
    private FbaShipment mustGetAuthorized(Long shipmentId) {
        return getShipment(shipmentId);
    }

    @Override
    public FbaShipment updateShipmentStatus(Long id, String status) {
        FbaShipment shipment = getShipment(id);
        shipment.setStatus(status);
        if ("DELIVERED".equals(status) || "CLOSED".equals(status)) {
            shipment.setActualArrival(LocalDate.now());
        }
        fbaShipmentMapper.updateById(shipment);
        return shipment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FbaShipment confirmShipment(Long id, String carrier, String trackingNo) {
        FbaShipment shipment = getShipment(id);
        if (!"READY_TO_SHIP".equals(shipment.getStatus()) && !"CREATED".equals(shipment.getStatus())) {
            throw new CodeErrorException("仅 READY_TO_SHIP/CREATED 状态可确认发货，当前状态：" + shipment.getStatus());
        }
        shipment.setCarrier(carrier);
        shipment.setMasterTrackingNo(trackingNo);
        shipment.setStatus("SHIPPED");
        fbaShipmentMapper.updateById(shipment);
        log.info("FBA货件已发货：shipmentNo={}, carrier={}, trackingNo={}", shipment.getShipmentNo(), carrier, trackingNo);
        return shipment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> allocateCosts(Long shipmentId) {
        FbaShipment shipment = getShipment(shipmentId);
        List<FbaShipmentItem> items = loadAllShipmentItemsForMutation(shipment);

        if (items.isEmpty()) {
            throw new CodeErrorException("货件无明细，无法分摊费用");
        }

        // 计算总数量用于按比例分摊。
        // quantity 允许为 null（同文件 :115 与 :335 都已按 null 处理过），mapToInt 直接拆箱会 NPE，
        // 一条脏明细就会让整个 allocateCosts 事务回滚。
        int totalQty = items.stream()
                .mapToInt(item -> item.getQuantity() == null ? 0 : item.getQuantity())
                .sum();
        if (totalQty <= 0) {
            throw new CodeErrorException("货件总数量为0，无法分摊费用");
        }

        BigDecimal totalFreight = shipment.getFreightCost() != null ? shipment.getFreightCost() : BigDecimal.ZERO;
        BigDecimal totalCustoms = shipment.getCustomsCost() != null ? shipment.getCustomsCost() : BigDecimal.ZERO;
        BigDecimal totalTax = shipment.getTaxCost() != null ? shipment.getTaxCost() : BigDecimal.ZERO;
        BigDecimal totalOther = shipment.getOtherCost() != null ? shipment.getOtherCost() : BigDecimal.ZERO;
        BigDecimal totalCost = totalFreight.add(totalCustoms).add(totalTax).add(totalOther);

        List<Map<String, Object>> allocationDetails = new ArrayList<>();

        for (FbaShipmentItem item : items) {
            // 数量缺失或非正的明细行不参与分摊：ratio 会变成 0，unitCost 那一步会除以 0 抛
            // ArithmeticException（totalQty>0 时这一行仍然进得来），整批分摊一起回滚。
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                log.warn("货件明细数量缺失或非正，跳过费用分摊：sku={} quantity={}", item.getSku(), item.getQuantity());
                continue;
            }
            BigDecimal ratio = BigDecimal.valueOf(item.getQuantity())
                    .divide(BigDecimal.valueOf(totalQty), 6, RoundingMode.HALF_UP);

            BigDecimal freightAlloc = totalFreight.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            BigDecimal customsAlloc = totalCustoms.add(totalTax).multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            BigDecimal otherAlloc = totalOther.multiply(ratio).setScale(2, RoundingMode.HALF_UP);

            BigDecimal itemTotalCost = item.getUnitCost() != null
                    ? item.getUnitCost().multiply(BigDecimal.valueOf(item.getQuantity()))
                    : BigDecimal.ZERO;
            itemTotalCost = itemTotalCost.add(freightAlloc).add(customsAlloc).add(otherAlloc);

            item.setFreightAllocation(freightAlloc);
            item.setCustomsAllocation(customsAlloc);
            item.setTotalCost(itemTotalCost);
            fbaShipmentItemMapper.updateById(item);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sku", item.getSku());
            detail.put("asin", item.getAsin());
            detail.put("quantity", item.getQuantity());
            detail.put("freightAllocation", freightAlloc);
            detail.put("customsAllocation", customsAlloc);
            detail.put("otherAllocation", otherAlloc);
            detail.put("totalCost", itemTotalCost);
            detail.put("unitCost", itemTotalCost.divide(BigDecimal.valueOf(item.getQuantity()), 2, RoundingMode.HALF_UP));
            allocationDetails.add(detail);
        }

        // 更新货件总成本
        shipment.setTotalCost(totalCost);
        fbaShipmentMapper.updateById(shipment);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipmentId", shipmentId);
        result.put("shipmentNo", shipment.getShipmentNo());
        result.put("totalQuantity", totalQty);
        result.put("totalFreight", totalFreight);
        result.put("totalCustoms", totalCustoms.add(totalTax));
        result.put("totalOther", totalOther);
        result.put("totalCost", totalCost);
        result.put("allocationDetails", allocationDetails);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> processReceipt(Long shipmentId, List<Map<String, Object>> receivedItems) {
        FbaShipment shipment = getShipment(shipmentId);
        if (receivedItems == null || receivedItems.isEmpty()) {
            throw new AttrIsNullException("验收明细不能为空");
        }
        List<FbaShipmentItem> items = loadAllShipmentItemsForMutation(shipment);
        Map<Long, FbaShipmentItem> itemsById = new LinkedHashMap<>();
        for (FbaShipmentItem item : items) {
            if (item.getId() == null) {
                throw new CodeErrorException("货件明细ID缺失，无法签收");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new CodeErrorException("货件明细数量非法：itemId=" + item.getId());
            }
            itemsById.put(item.getId(), item);
        }

        List<ReceiptLine> receiptLines = parseReceiptLines(receivedItems, itemsById);
        List<Map<String, Object>> results = new ArrayList<>();
        boolean hasDiscrepancy = false;

        for (ReceiptLine line : receiptLines) {
            FbaShipmentItem item = itemsById.get(line.itemId());
            if (item.getReceivedQuantity() != null && item.getReceivedQuantity() > 0
                    && !item.getReceivedQuantity().equals(line.receivedQty())) {
                throw new CodeErrorException("明细已签收，禁止重复或变更签收数量：itemId="
                        + line.itemId() + ", 已签收=" + item.getReceivedQuantity()
                        + ", 本次=" + line.receivedQty());
            }

            int discrepancy = line.receivedQty() - item.getQuantity();
            item.setReceivedQuantity(line.receivedQty());
            if (fbaShipmentItemMapper.updateById(item) != 1) {
                throw new CodeErrorException("签收数量更新失败：itemId=" + line.itemId());
            }

            if (line.receivedQty() > 0) {
                receiveBatch(shipmentId, line.itemId(), line.receivedQty());
            }

            Map<String, Object> itemResult = new LinkedHashMap<>();
            itemResult.put("sku", item.getSku());
            itemResult.put("expectedQty", item.getQuantity());
            itemResult.put("receivedQty", line.receivedQty());
            itemResult.put("discrepancy", discrepancy);
            if (discrepancy != 0) {
                hasDiscrepancy = true;
                itemResult.put("status", discrepancy > 0 ? "OVER_RECEIVING" : "SHORT_RECEIVING");
            } else {
                itemResult.put("status", "MATCHED");
            }
            results.add(itemResult);
        }

        boolean allReceived = items.stream().allMatch(item ->
                item.getQuantity() != null && item.getQuantity() > 0
                        && item.getReceivedQuantity() != null
                        && item.getReceivedQuantity() >= item.getQuantity());
        long pendingItemCount = items.stream().filter(item ->
                item.getQuantity() == null || item.getQuantity() <= 0
                        || item.getReceivedQuantity() == null
                        || item.getReceivedQuantity() < item.getQuantity()).count();

        shipment.setStatus(allReceived ? "CLOSED" : "RECEIVING");
        shipment.setActualArrival(LocalDate.now());
        fbaShipmentMapper.updateById(shipment);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipmentId", shipmentId);
        result.put("shipmentNo", shipment.getShipmentNo());
        result.put("hasDiscrepancy", hasDiscrepancy);
        result.put("allReceived", allReceived);
        result.put("pendingItemCount", pendingItemCount);
        result.put("itemResults", results);

        if (hasDiscrepancy) {
            log.warn("FBA货件存在签收差异：shipmentNo={}, 详情={}", shipment.getShipmentNo(), results);
        }
        return result;
    }

    private List<ReceiptLine> parseReceiptLines(List<Map<String, Object>> receivedItems,
                                                Map<Long, FbaShipmentItem> itemsById) {
        List<ReceiptLine> lines = new ArrayList<>();
        Set<Long> seenItemIds = new HashSet<>();
        for (Map<String, Object> received : receivedItems) {
            if (received == null || received.get("itemId") == null || received.get("receivedQty") == null) {
                throw new AttrIsNullException("验收明细缺 itemId/receivedQty");
            }
            final Long itemId;
            final Integer receivedQty;
            try {
                itemId = Long.valueOf(received.get("itemId").toString());
                receivedQty = Integer.valueOf(received.get("receivedQty").toString());
            } catch (NumberFormatException e) {
                throw new AttrIsNullException("验收明细 itemId/receivedQty 非法数字");
            }
            if (receivedQty < 0) {
                throw new AttrIsNullException("验收数量不能为负数：itemId=" + itemId);
            }
            if (!itemsById.containsKey(itemId)) {
                throw new AttrIsNullException("货件明细不存在：itemId=" + itemId);
            }
            if (!seenItemIds.add(itemId)) {
                throw new CodeErrorException("同一货件明细在签收请求中重复提交：itemId=" + itemId);
            }
            lines.add(new ReceiptLine(itemId, receivedQty));
        }
        return lines;
    }

    private record ReceiptLine(Long itemId, Integer receivedQty) {
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryBatch receiveBatch(Long shipmentId, Long shipmentItemId, Integer receivedQty) {
        if (shipmentId == null || shipmentItemId == null) {
            throw new AttrIsNullException("货件ID和货件明细ID不能为空");
        }
        if (receivedQty == null) {
            throw new AttrIsNullException("入库数量不能为空");
        }
        if (receivedQty <= 0) {
            throw new CodeErrorException("入库数量必须大于0");
        }
        FbaShipment shipment = getShipment(shipmentId);
        FbaShipmentItem item = fbaShipmentItemMapper.selectById(shipmentItemId);
        if (item == null) {
            throw new AttrIsNullException("货件明细不存在：itemId=" + shipmentItemId);
        }
        // 归属一致性：明细必须挂靠当前货件，防止用自店货件 ID 套他店明细建批次
        if (!shipmentId.equals(item.getFbaShipmentId())) {
            throw new CodeErrorException("货件明细不属于当前货件：itemId=" + shipmentItemId);
        }
        if (item.getQuantity() == null || item.getQuantity() <= 0) {
            throw new CodeErrorException("货件明细数量必须大于0：itemId=" + shipmentItemId);
        }

        if (item.getReceivedQuantity() == null) {
            item.setReceivedQuantity(0);
        }
        if (item.getReceivedQuantity() > 0 && !item.getReceivedQuantity().equals(receivedQty)) {
            throw new CodeErrorException("货件明细已签收，禁止变更入库数量：itemId=" + shipmentItemId
                    + ", 已签收=" + item.getReceivedQuantity() + ", 本次=" + receivedQty);
        }

        // 明细已绑定批次时，重复调用必须返回原批次，不能再次插入库存。
        if (item.getBatchNo() != null) {
            InventoryBatch existing = inventoryBatchMapper.selectOne(
                    new LambdaQueryWrapper<InventoryBatch>()
                            .eq(InventoryBatch::getBatchNo, item.getBatchNo()));
            if (existing == null) {
                throw new CodeErrorException("货件明细已绑定批次号但批次不存在：itemId=" + shipmentItemId
                        + ", batchNo=" + item.getBatchNo());
            }
            return existing;
        }

        if (item.getReceivedQuantity() == 0) {
            item.setReceivedQuantity(receivedQty);
        }

        // 计算批次单位成本（采购成本 + 分摊的头程费用）
        BigDecimal unitCost = item.getUnitCost() != null ? item.getUnitCost() : BigDecimal.ZERO;
        if (item.getFreightAllocation() != null) {
            unitCost = unitCost.add(item.getFreightAllocation().divide(BigDecimal.valueOf(item.getQuantity()), 4, RoundingMode.HALF_UP));
        }
        if (item.getCustomsAllocation() != null) {
            unitCost = unitCost.add(item.getCustomsAllocation().divide(BigDecimal.valueOf(item.getQuantity()), 4, RoundingMode.HALF_UP));
        }

        BigDecimal totalCost = unitCost.multiply(BigDecimal.valueOf(receivedQty)).setScale(2, RoundingMode.HALF_UP);

        InventoryBatch batch = new InventoryBatch();
        batch.setShopId(shipment.getShopId());
        batch.setBatchNo(BizNoGenerator.next("BAT" + LocalDate.now().format(BATCH_FMT)));
        batch.setPurchaseOrderId(null);
        batch.setInboundOrderId(shipmentId);
        batch.setShipmentItemId(shipmentItemId);
        batch.setSku(item.getSku());
        batch.setAsin(item.getAsin());
        batch.setWarehouseId(shipment.getWarehouseId());
        batch.setQuantity(receivedQty);
        batch.setAvailableQuantity(receivedQty);
        batch.setUnitCost(unitCost.setScale(2, RoundingMode.HALF_UP));
        batch.setFreightCost(item.getFreightAllocation());
        batch.setCustomsCost(item.getCustomsAllocation());
        batch.setOtherCost(BigDecimal.ZERO);
        batch.setTotalCost(totalCost);
        batch.setInboundDate(LocalDate.now());
        batch.setStatus("ACTIVE");

        final int inserted;
        try {
            inserted = inventoryBatchMapper.insert(batch);
        } catch (DuplicateKeyException e) {
            // MySQL REPEATABLE READ 下普通 SELECT 可能沿用旧快照，冲突回查必须使用当前读。
            InventoryBatch existing = inventoryBatchMapper.selectOne(
                    new LambdaQueryWrapper<InventoryBatch>()
                            .eq(InventoryBatch::getShipmentItemId, shipmentItemId)
                            .last("FOR UPDATE"));
            if (existing == null || existing.getBatchNo() == null) {
                throw new CodeErrorException("库存批次幂等冲突且无法读取已有批次：itemId=" + shipmentItemId);
            }
            // 并发冲突赢家已经落库；本事务补齐明细绑定，后续重试直接复用批次。
            item.setBatchNo(existing.getBatchNo());
            if (fbaShipmentItemMapper.updateById(item) != 1) {
                throw new CodeErrorException("库存批次幂等冲突后绑定货件明细失败：itemId=" + shipmentItemId);
            }
            return existing;
        }
        if (inserted != 1) {
            throw new CodeErrorException("库存批次写入失败：itemId=" + shipmentItemId);
        }

        item.setBatchNo(batch.getBatchNo());
        if (fbaShipmentItemMapper.updateById(item) != 1) {
            throw new CodeErrorException("库存批次绑定货件明细失败：itemId=" + shipmentItemId);
        }

        log.info("批次入库完成：batchNo={}, sku={}, qty={}, unitCost={}", batch.getBatchNo(), batch.getSku(), receivedQty, batch.getUnitCost());
        return batch;
    }
    @Override
    public PageResult<InventoryBatch> listBatchesBySku(Long shopId, String sku, PageRequest page) {
        validateBatchScope(shopId, sku);
        if (page == null) {
            throw new CodeErrorException("分页参数不能为空");
        }
        return queryBatchPage(shopId, sku, page);
    }

    @Override
    public BatchCostSummary getBatchCostSummary(Long shopId, String sku) {
        validateBatchScope(shopId, sku);
        return inventoryBatchMapper.sumActiveBatchCost(shopId, sku);
    }

    /**
     * 查询一页 FIFO 批次。调用方必须先完成店铺/SKU 校验。
     */
    private PageResult<InventoryBatch> queryBatchPage(Long shopId, String sku, PageRequest page) {
        LambdaQueryWrapper<InventoryBatch> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryBatch::getShopId, shopId)
               .eq(InventoryBatch::getSku, sku)
               .eq(InventoryBatch::getStatus, "ACTIVE");

        BatchCursor cursor = parseBatchCursor(page);
        if (cursor != null) {
            wrapper.and(w -> w
                    .gt(InventoryBatch::getInboundDate, cursor.inboundDate())
                    .or(n -> n.eq(InventoryBatch::getInboundDate, cursor.inboundDate())
                              .gt(InventoryBatch::getId, cursor.id())));
        }

        wrapper.orderByAsc(InventoryBatch::getInboundDate)
               .orderByAsc(InventoryBatch::getId)
               .last("LIMIT " + page.probeSize());

        return PageResult.of(inventoryBatchMapper.selectList(wrapper), page.size(), item -> {
            if (item.getInboundDate() == null || item.getId() == null) {
                throw new CodeErrorException("库存批次缺少 FIFO 排序键：batchId=" + item.getId());
            }
            return PageRequest.encodeCursor(item.getInboundDate() + "|" + item.getId());
        });
    }

    /**
     * FIFO 出库读取全量 ACTIVE 批次。
     * <p>公开列表已经分页；若出库误用第一页会静默漏扣并算错成本，
     * 因此这里循环读取，超过安全上限则 fail-closed。</p>
     */
    private List<InventoryBatch> loadAllBatchesForFifo(Long shopId, String sku) {
        validateBatchScope(shopId, sku);
        List<InventoryBatch> all = new ArrayList<>();
        PageRequest page = PageRequest.first(BATCH_PAGE_SIZE);
        while (true) {
            PageResult<InventoryBatch> current = queryBatchPage(shopId, sku, page);
            all.addAll(current.items());
            if (all.size() > MAX_BATCHES_FOR_FIFO) {
                throw new CodeErrorException("SKU 的 ACTIVE 批次超过安全上限 "
                        + MAX_BATCHES_FOR_FIFO + " 条，无法安全完成 FIFO 出库；请先归档或清理历史批次");
            }
            if (!current.hasMore()) {
                return all;
            }
            page = PageRequest.of(BATCH_PAGE_SIZE, current.nextCursor());
        }
    }

    private void validateBatchScope(Long shopId, String sku) {
        if (shopId == null) {
            throw new AttrIsNullException("店铺ID不能为空");
        }
        if (sku == null || sku.isBlank()) {
            throw new AttrIsNullException("SKU不能为空");
        }
        if (!UserContext.isShopAllowed(shopId)) {
            throw new CodeErrorException("无权查询该店铺的库存批次：shopId=" + shopId);
        }
    }

    private BatchCursor parseBatchCursor(PageRequest page) {
        if (page == null) {
            throw new CodeErrorException("分页参数不能为空");
        }
        if (!page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("库存批次游标非法：" + page.payload());
        }
        try {
            LocalDate inboundDate = LocalDate.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new InvalidParamException("库存批次游标非法：id 必须 > 0，实际 " + id);
            }
            return new BatchCursor(inboundDate, id);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new InvalidParamException("库存批次游标非法：" + page.payload());
        }
    }

    private record BatchCursor(LocalDate inboundDate, long id) {
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> fifoOutbound(Long shopId, String sku, Integer quantity) {
        if (quantity == null) {
            throw new AttrIsNullException("出库数量不能为空");
        }
        if (quantity <= 0) {
            throw new CodeErrorException("出库数量必须大于0");
        }
        List<InventoryBatch> batches = loadAllBatchesForFifo(shopId, sku);
        long availableTotal = batches.stream()
                .map(InventoryBatch::getAvailableQuantity)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .filter(value -> value > 0)
                .sum();
        if (availableTotal < quantity) {
            throw new CodeErrorException("FIFO出库库存不足：shopId=" + shopId
                    + ", sku=" + sku + ", 请求=" + quantity + ", 可用=" + availableTotal);
        }

        List<Map<String, Object>> outboundDetails = new ArrayList<>();
        int remaining = quantity;

        for (InventoryBatch batch : batches) {
            if (remaining <= 0) break;
            int availableQty = batch.getAvailableQuantity() == null ? 0 : batch.getAvailableQuantity();
            if (availableQty <= 0) continue;

            int deductQty = Math.min(availableQty, remaining);
            int affected = inventoryBatchMapper.decreaseAvailableQuantityAtomic(
                    batch.getId(), shopId, sku, deductQty);
            if (affected != 1) {
                throw new CodeErrorException("FIFO出库并发冲突或库存不足，事务已回滚：shopId=" + shopId
                        + ", sku=" + sku + ", batchNo=" + batch.getBatchNo()
                        + ", 请求扣减=" + deductQty);
            }

            BigDecimal subtotal = batch.getUnitCost().multiply(BigDecimal.valueOf(deductQty));

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("batchNo", batch.getBatchNo());
            detail.put("sku", batch.getSku());
            detail.put("quantity", deductQty);
            detail.put("unitCost", batch.getUnitCost());
            detail.put("subtotal", subtotal);
            detail.put("inboundDate", batch.getInboundDate());
            outboundDetails.add(detail);

            remaining -= deductQty;
        }

        if (remaining > 0) {
            log.warn("FIFO出库库存不足：shopId={}, sku={}, 请求={}, 缺={}", shopId, sku, quantity, remaining);
        }

        return outboundDetails;
    }
}
