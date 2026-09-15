package com.amz.service.impl;

import com.amz.dto.ImportReport;
import com.amz.dto.ImportShipmentDTO;
import com.amz.dto.ImportTrackingDTO;
import com.amz.dto.ImportTrackingEventDTO;
import com.amz.dto.IngestOutcome;
import com.amz.mapper.ShipmentMapper;
import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.service.LogisticsImportService;
import com.amz.service.TrackingIngestService;
import com.amz.service.TrackingStatusMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 物流数据外部导入实现。
 * <p>
 * <b>事务边界（刻意如此）：</b>本类<b>不在批量方法上加 {@code @Transactional}</b>。
 * 导入动辄上千行，若整批一个事务，任何一行出错都会让整批回滚，
 * 使用者只看到「全部失败」而不知道哪行有问题，被迫反复试错。
 * 改为一行为一个事务（轨迹写入经 {@link TrackingIngestService} 的代理进入独立事务），
 * 出错行记入报告、其余行照常入库，使用者改掉错误行重导即可。
 * 代价是导入不是原子的——对「外部数据灌入」这类场景，可诊断性比原子性更重要。
 */
@Slf4j
@Service
public class LogisticsImportServiceImpl implements LogisticsImportService {

    /** 货件合法状态集合（注意与轨迹状态的差异：含 CUSTOMS/DELIVERED/RECEIVED 等 FBA 环节状态） */
    private static final Set<String> SHIPMENT_STATUSES = Set.of(
            "CREATED", "IN_TRANSIT", "CUSTOMS", "DELIVERED", "RECEIVED", "CLOSED", "DELAYED", "EXCEPTION");

    /** 取数来源偏好合法值 */
    private static final Set<String> DATA_SOURCES = Set.of("IMPORT", "API", "AUTO");

    private static final String DEFAULT_SHIPMENT_STATUS = "CREATED";

    private static final String DEFAULT_DATA_SOURCE = "IMPORT";

    @Autowired
    private ShipmentMapper shipmentMapper;

    /** 轨迹落库统一入口，与入口 B（API 拉取）共用，保证状态映射与去重口径唯一 */
    @Autowired
    private TrackingIngestService trackingIngestService;

    // ================================================================ 货件主单导入

    @Override
    public ImportReport importShipments(List<ImportShipmentDTO> rows, Long shopId) {
        ImportReport report = new ImportReport();
        if (shopId == null) {
            report.addError(0, "缺少 shopId，无法确定货件归属店铺");
            return report;
        }
        if (rows == null || rows.isEmpty()) {
            return report;
        }
        report.setTotalRows(rows.size());

        // 批内去重：同一文件常因多次导出拼接而出现重复货件编号。
        // 若不去重，后一条会静默覆盖前一条且「新建数」虚高，使用者无法察觉哪份数据生效了。
        Set<String> seenInBatch = new HashSet<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 1;
            try {
                String shipmentNo = upsertOne(rows.get(i), shopId, seenInBatch, rowNo, report);
                if (shipmentNo != null) {
                    report.countSuccess();
                }
            } catch (IllegalArgumentException e) {
                // 可预期的数据问题：计入行级错误，继续处理后续行
                report.countFailure();
                report.addError(rowNo, e.getMessage());
            } catch (Exception e) {
                report.countFailure();
                report.addError(rowNo, "处理失败：" + e.getClass().getSimpleName());
                log.error("货件导入行处理异常：row={} shopId={}", rowNo, shopId, e);
            }
        }

        log.info("货件导入完成：shopId={} 总行={} 成功={} 失败={} 新建={} 更新={}",
                shopId, report.getTotalRows(), report.getSucceededRows(), report.getFailedRows(),
                report.getCreatedShipments(), report.getUpdatedShipments());
        return report;
    }

    /**
     * 处理单行货件导入。
     *
     * @return 成功时返回货件编号；校验失败由内部抛出 {@link IllegalArgumentException}
     */
    private String upsertOne(ImportShipmentDTO row, Long shopId, Set<String> seenInBatch,
                             int rowNo, ImportReport report) {
        if (row == null) {
            throw new IllegalArgumentException("该行为空");
        }
        String shipmentNo = clean(row.getShipmentNo());
        if (shipmentNo == null) {
            throw new IllegalArgumentException(
                    "shipmentNo 不能为空——导入以货件编号为幂等键，缺此字段既无法匹配也无法补建");
        }
        if (!seenInBatch.add(shipmentNo)) {
            throw new IllegalArgumentException("货件编号在本批次内重复：" + shipmentNo);
        }

        String status = validateShipmentStatus(row.getStatus());
        String dataSource = validateDataSource(row.getDataSource());

        List<Shipment> existing = shipmentMapper.selectList(new LambdaQueryWrapper<Shipment>()
                .eq(Shipment::getShipmentNo, shipmentNo));

        if (existing.isEmpty()) {
            Shipment created = buildShipment(row, shipmentNo, shopId, status, dataSource);
            shipmentMapper.insert(created);
            report.setCreatedShipments(report.getCreatedShipments() + 1);
            return shipmentNo;
        }

        Shipment target = existing.get(0);
        // 租户隔离：货件编号全局唯一，若该编号已属他店，既不更新也不重复插入。
        // 提示语刻意不说明该编号被哪家店铺占用，避免用导入接口探测他店数据是否存在。
        if (target.getShopId() != null && !target.getShopId().equals(shopId)) {
            throw new IllegalArgumentException("货件编号已存在且不属于当前店铺，无法导入：" + shipmentNo);
        }
        if (fillShipment(target, row, status, dataSource)) {
            shipmentMapper.updateById(target);
            report.setUpdatedShipments(report.getUpdatedShipments() + 1);
        }
        // 无字段变化时不写库：重复导入同一文件不应产生任何 update，这是幂等的直接体现
        return shipmentNo;
    }

    private Shipment buildShipment(ImportShipmentDTO row, String shipmentNo, Long shopId,
                                   String status, String dataSource) {
        Shipment shipment = new Shipment();
        shipment.setShipmentNo(shipmentNo);
        // 店铺一律取接口入参，不接受请求体自带值，避免调用方自行选择租户
        shipment.setShopId(shopId);
        shipment.setFbaShipmentId(clean(row.getFbaShipmentId()));
        shipment.setShippingMethod(clean(row.getShippingMethod()));
        shipment.setCarrier(clean(row.getCarrier()));
        shipment.setMasterTrackingNo(clean(row.getMasterTrackingNo()));
        shipment.setOriginPort(clean(row.getOriginPort()));
        shipment.setDestinationPort(clean(row.getDestinationPort()));
        shipment.setFbaWarehouseAddress(clean(row.getFbaWarehouseAddress()));
        shipment.setBoxCount(row.getBoxCount());
        shipment.setWeight(row.getWeight());
        shipment.setFreightCost(row.getFreightCost());
        shipment.setEta(clean(row.getEta()));
        shipment.setStatus(status == null ? DEFAULT_SHIPMENT_STATUS : status);
        shipment.setDataSource(dataSource == null ? DEFAULT_DATA_SOURCE : dataSource);
        return shipment;
    }

    /**
     * 用导入值补齐货件已存在的字段。
     * <p>
     * 只覆盖「本次给出了非空值」的字段，未给出的保持原值：导入常分批进行
     * （先导主档、后补 ETA），若用空值覆盖会把先前导入的内容清空。
     *
     * @return 是否至少有一个字段发生变化（决定要不要落库）
     */
    private boolean fillShipment(Shipment target, ImportShipmentDTO row, String status, String dataSource) {
        boolean changed = false;
        changed |= apply(clean(row.getFbaShipmentId()), target::getFbaShipmentId, target::setFbaShipmentId);
        changed |= apply(clean(row.getShippingMethod()), target::getShippingMethod, target::setShippingMethod);
        changed |= apply(clean(row.getCarrier()), target::getCarrier, target::setCarrier);
        changed |= apply(clean(row.getMasterTrackingNo()), target::getMasterTrackingNo, target::setMasterTrackingNo);
        changed |= apply(clean(row.getOriginPort()), target::getOriginPort, target::setOriginPort);
        changed |= apply(clean(row.getDestinationPort()), target::getDestinationPort, target::setDestinationPort);
        changed |= apply(clean(row.getFbaWarehouseAddress()), target::getFbaWarehouseAddress, target::setFbaWarehouseAddress);
        changed |= apply(row.getBoxCount(), target::getBoxCount, target::setBoxCount);
        changed |= apply(row.getWeight(), target::getWeight, target::setWeight);
        changed |= apply(row.getFreightCost(), target::getFreightCost, target::setFreightCost);
        changed |= apply(clean(row.getEta()), target::getEta, target::setEta);
        changed |= apply(status, target::getStatus, target::setStatus);
        changed |= apply(dataSource, target::getDataSource, target::setDataSource);
        return changed;
    }

    /** 仅在导入值非空且与原值不同时写入，返回是否发生变更 */
    private static <T> boolean apply(T incoming, Supplier<T> getter, Consumer<T> setter) {
        if (incoming == null || Objects.equals(incoming, getter.get())) {
            return false;
        }
        setter.accept(incoming);
        return true;
    }

    // ================================================================ 运单轨迹导入

    @Override
    public ImportReport importTracking(List<ImportTrackingDTO> rows, Long shopId) {
        ImportReport report = new ImportReport();
        if (shopId == null) {
            report.addError(0, "缺少 shopId，无法确定轨迹归属店铺");
            return report;
        }
        if (rows == null || rows.isEmpty()) {
            return report;
        }
        report.setTotalRows(rows.size());

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 1;
            try {
                ingestOneTracking(rows.get(i), shopId, rowNo, report);
            } catch (IllegalArgumentException e) {
                report.countFailure();
                report.addError(rowNo, e.getMessage());
            } catch (Exception e) {
                report.countFailure();
                report.addError(rowNo, "处理失败：" + e.getClass().getSimpleName());
                log.error("轨迹导入行处理异常：row={} shopId={}", rowNo, shopId, e);
            }
        }

        log.info("轨迹导入完成：shopId={} 总运单={} 失败={} 写入={} 跳过={} 未匹配={}",
                shopId, report.getTotalRows(), report.getFailedRows(), report.getAcceptedEvents(),
                report.getSkippedEvents(), report.getUnmatched().size());
        return report;
    }

    private void ingestOneTracking(ImportTrackingDTO row, Long shopId, int rowNo, ImportReport report) {
        if (row == null) {
            throw new IllegalArgumentException("该行为空");
        }
        String shipmentNo = clean(row.getShipmentNo());
        String trackingNo = clean(row.getTrackingNo());
        if (shipmentNo == null && trackingNo == null) {
            throw new IllegalArgumentException("shipmentNo 与 trackingNo 至少提供一个，否则无法定位货件");
        }
        if (row.getEvents() == null || row.getEvents().isEmpty()) {
            throw new IllegalArgumentException("events 为空，无可导入的轨迹点");
        }

        List<TrackingEvent> events = new ArrayList<>(row.getEvents().size());
        int eventRowBase = 1;
        for (ImportTrackingEventDTO eventDto : row.getEvents()) {
            events.add(toEvent(eventDto, rowNo, eventRowBase++));
        }

        // 店铺约束在落库核心内部生效（按 shopId 过滤匹配），因此此处直接传入原始标识即可：
        // 他店的运单号不会命中任何货件，结果是「未匹配」而非越权写入。
        IngestOutcome outcome = trackingIngestService.ingest(
                shipmentNo, trackingNo, events, TrackingIngestService.SOURCE_IMPORT, shopId);

        if (!outcome.isMatched()) {
            report.countFailure();
            report.addUnmatched(shipmentNo != null ? shipmentNo : trackingNo);
            return;
        }

        report.countSuccess();
        report.setAcceptedEvents(report.getAcceptedEvents() + outcome.getAccepted());
        report.setSkippedEvents(report.getSkippedEvents() + outcome.getSkipped());
        if (outcome.isStatusChanged()) {
            report.setStatusChangedShipments(report.getStatusChangedShipments() + 1);
        }
    }

    /**
     * 转换轨迹点。
     * <p>
     * {@code rawStatus} 显式写入承运商原文：落库核心在「原文与状态码都给」时，
     * 会用原文做映射、并保留原文以便回溯；若不在此赋值，原文会在归一化时被状态码覆盖而丢失。
     */
    private TrackingEvent toEvent(ImportTrackingEventDTO dto, int rowNo, int eventNo) {
        if (dto == null) {
            throw new IllegalArgumentException("第 " + rowNo + " 行的第 " + eventNo + " 个轨迹点为空");
        }
        String rawStatus = clean(dto.getStatus());
        String statusCode = clean(dto.getStatusCode());
        if (statusCode != null && !TrackingStatusMapper.INTERNAL_STATUSES.contains(statusCode.toUpperCase(Locale.ROOT))) {
            // 拒绝而非兜底：状态码由调用方自行映射，若拼错却按文本二次映射，
            // 会得到一个看似成功但语义错误的状态，比直接报错难排查得多
            throw new IllegalArgumentException("第 " + rowNo + " 行的第 " + eventNo
                    + " 个轨迹点 statusCode 非法：" + statusCode
                    + "，允许值 " + TrackingStatusMapper.INTERNAL_STATUSES);
        }
        if (rawStatus == null && statusCode == null) {
            throw new IllegalArgumentException("第 " + rowNo + " 行的第 " + eventNo
                    + " 个轨迹点缺少状态：请提供 status（承运商原文）或 statusCode（内部状态码）");
        }

        TrackingEvent event = new TrackingEvent();
        event.setRawStatus(rawStatus);
        event.setEventStatus(statusCode != null ? statusCode.toUpperCase(Locale.ROOT) : rawStatus);
        event.setLocation(clean(dto.getLocation()));
        event.setDescription(clean(dto.getDescription()));
        event.setEventTime(clean(dto.getEventTime()));
        event.setLongitude(dto.getLongitude());
        event.setLatitude(dto.getLatitude());
        return event;
    }

    // ================================================================ 校验与工具

    /** 校验货件状态；空值返回 null（表示不改状态），非法值抛出可读错误 */
    private String validateShipmentStatus(String status) {
        String value = clean(status);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!SHIPMENT_STATUSES.contains(upper)) {
            throw new IllegalArgumentException("status 非法：" + status + "，允许值 " + SHIPMENT_STATUSES
                    + "（承运商原文请走轨迹导入的 status 字段，此处只接受系统内部货件状态）");
        }
        return upper;
    }

    /** 校验取数来源偏好；空值返回 null（新建时取默认 IMPORT） */
    private String validateDataSource(String dataSource) {
        String value = clean(dataSource);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!DATA_SOURCES.contains(upper)) {
            throw new IllegalArgumentException("dataSource 非法：" + dataSource
                    + "，允许值 " + DATA_SOURCES);
        }
        return upper;
    }

    /** 去空白；空白串统一归一为 null，避免 " " 被当作有效值写入 */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
