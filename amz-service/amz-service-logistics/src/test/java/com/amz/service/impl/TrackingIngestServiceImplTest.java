package com.amz.service.impl;

import com.amz.dto.IngestOutcome;
import com.amz.mapper.ShipmentMapper;
import com.amz.mapper.TrackingEventMapper;
import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.service.TrackingIngestService;
import com.amz.service.TrackingStatusMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 轨迹落库核心单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 这一层是「外部导入」与「第三方 API 拉取」两条入口的唯一共用落库点，
 * 因此测试重点不在 CRUD 是否跑通，而在<b>双入口并存时的行为契约</b>：
 * <ul>
 *   <li>增量合并而非先删后插 —— 否则一条链路会抹掉另一条写入的历史轨迹；</li>
 *   <li>按 (状态, 时间) 指纹幂等 —— 重复导入不产生重复轨迹；</li>
 *   <li>时间归一化后再判重 —— 同一事件的两种时区写法必须判为同一条；</li>
 *   <li>状态映射口径 —— 到港/派送中不得被当作已送达；</li>
 *   <li>取数时间刷新 —— 含「查询成功但无新轨迹」，否则调度轮换会失效。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("轨迹落库核心单元测试")
class TrackingIngestServiceImplTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Mock
    private ShipmentMapper shipmentMapper;

    @Mock
    private TrackingEventMapper trackingEventMapper;

    /** 用真实映射器（而非 mock）：状态映射本身也在本类的契约范围内 */
    @Spy
    private TrackingStatusMapper statusMapper = new TrackingStatusMapper();

    @InjectMocks
    private TrackingIngestServiceImpl ingestService;

    // ================================================================ 匹配

    @Test
    @DisplayName("匹配不到货件 → 返回未匹配结果，不写任何数据")
    void testUnmatchedShipment() {
        when(shipmentMapper.selectList(any())).thenReturn(Collections.emptyList());

        IngestOutcome outcome = ingestService.ingest("SHP-NOT-EXIST", "TRK-1",
                List.of(event("已开船", "2026-08-05 09:00:00")),
                TrackingIngestService.SOURCE_IMPORT, 1L);

        assertFalse(outcome.isMatched(), "未匹配到货件时应回吐未匹配结果供调用方汇总");
        verify(trackingEventMapper, never()).insert(any(TrackingEvent.class));
        verify(shipmentMapper, never()).updateById(any(Shipment.class));
    }

    @Test
    @DisplayName("匹配到货件但本次无轨迹 → 仍刷新取数时间（调度轮换依赖）")
    void testEmptyEventsStillTouchesLastTrackTime() {
        Shipment shipment = shipment(10L, "SHP-1", "CREATED");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));

        IngestOutcome outcome = ingestService.ingest("SHP-1", "TRK-1",
                Collections.emptyList(), TrackingIngestService.SOURCE_API, 1L);

        assertTrue(outcome.isMatched());
        assertEquals(0, outcome.getAccepted());
        assertNotNull(shipment.getLastTrackTime(),
                "无轨迹也必须刷新取数时间，否则该货件会被调度每轮反复选中");
        verify(shipmentMapper).updateById(shipment);
    }

    // ================================================================ 幂等与合并

    @Test
    @DisplayName("新增轨迹点 → 插入并回写货件状态")
    void testIngestInsertsNewEventsAndUpdatesStatus() {
        Shipment shipment = shipment(10L, "SHP-1", "CREATED");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));
        when(trackingEventMapper.selectList(any())).thenReturn(Collections.emptyList());

        IngestOutcome outcome = ingestService.ingest("SHP-1", "TRK-1",
                List.of(event("已开船", "2026-08-05 09:00:00")),
                TrackingIngestService.SOURCE_IMPORT, 1L);

        assertTrue(outcome.isMatched());
        assertEquals(1, outcome.getAccepted());
        assertEquals(0, outcome.getSkipped());
        assertTrue(outcome.isStatusChanged());
        assertEquals("IN_TRANSIT", outcome.getShipmentStatus());
        assertEquals("IN_TRANSIT", shipment.getStatus(), "货件状态应被回写");
        verify(trackingEventMapper).insert(any(TrackingEvent.class));
        verify(shipmentMapper).updateById(shipment);
    }

    @Test
    @DisplayName("已存在同一轨迹点 → 跳过而非重复插入（幂等）")
    void testIdempotentSkipOnSameFingerprint() {
        Shipment shipment = shipment(10L, "SHP-1", "IN_TRANSIT");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));
        // 库中已有：IN_TRANSIT @ 2026-08-05 01:00:00 (UTC)
        when(trackingEventMapper.selectList(any()))
                .thenReturn(List.of(existing("IN_TRANSIT", "2026-08-05 01:00:00")));

        // 同一事件换一种时区写法提交：+08:00 的 09:00 就是 UTC 01:00
        IngestOutcome outcome = ingestService.ingest("SHP-1", "TRK-1",
                List.of(event("IN_TRANSIT", "2026-08-05T09:00:00+08:00")),
                TrackingIngestService.SOURCE_API, 1L);

        assertEquals(0, outcome.getAccepted(), "归一化后指纹一致，应判为已存在");
        assertEquals(1, outcome.getSkipped());
        verify(trackingEventMapper, never()).insert(any(TrackingEvent.class));
    }

    @Test
    @DisplayName("双入口合并：只追加新点，绝不删除既有轨迹")
    void testCrossEntryMergeNeverDeletes() {
        Shipment shipment = shipment(10L, "SHP-1", "IN_TRANSIT");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));
        // 既有轨迹来自 IMPORT 入口
        when(trackingEventMapper.selectList(any()))
                .thenReturn(List.of(existing("CREATED", "2026-08-01 02:00:00")));

        // API 入口补一条更晚的轨迹
        IngestOutcome outcome = ingestService.ingest("SHP-1", "TRK-1",
                List.of(event("已到港", "2026-08-20 10:00:00")),
                TrackingIngestService.SOURCE_API, 1L);

        assertEquals(1, outcome.getAccepted(), "新点应写入");
        assertEquals(0, outcome.getSkipped(), "旧点不应被计为跳过");
        // 只允许「查一次 + 插一条」：任何 delete 调用都会破坏另一条入口写入的历史轨迹
        verify(trackingEventMapper, times(1)).selectList(any());
        verify(trackingEventMapper, times(1)).insert(any(TrackingEvent.class));
        verifyNoMoreInteractions(trackingEventMapper);
    }

    @Test
    @DisplayName("归一化：来源与原始状态文本被写入，主键被清空")
    void testNormalizeWritesSourceAndRawStatus() {
        Shipment shipment = shipment(10L, "SHP-1", "CREATED");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));
        when(trackingEventMapper.selectList(any())).thenReturn(Collections.emptyList());

        TrackingEvent incoming = event("清关完成（已放行）", "2026-08-20T21:30:00-07:00");
        incoming.setId(999L);

        ingestService.ingest("SHP-1", "TRK-1", List.of(incoming),
                TrackingIngestService.SOURCE_API, 1L);

        assertEquals(TrackingIngestService.SOURCE_API, incoming.getSource());
        assertEquals("清关完成（已放行）", incoming.getRawStatus(), "承运商原文须保留以便回溯映射偏差");
        assertEquals("CUSTOMS_CLEARANCE", incoming.getEventStatus());
        assertEquals("2026-08-21 04:30:00", incoming.getEventTime(),
                "带偏移的时间应归一为 UTC 的固定格式，保证字典序等于时间序");
        assertNull(incoming.getId(), "主键由数据库生成，避免导入旧库数据时与既有行冲突");
    }

    // ================================================================ 状态机口径

    @Test
    @DisplayName("回归：ARRIVED（到港）不得被当作已送达")
    void testArrivedIsNotDelivered() {
        Shipment shipment = shipment(10L, "SHP-1", "CUSTOMS");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));
        when(trackingEventMapper.selectList(any())).thenReturn(Collections.emptyList());

        IngestOutcome outcome = ingestService.ingest("SHP-1", "TRK-1",
                List.of(event("已到港", "2026-08-20 10:00:00")),
                TrackingIngestService.SOURCE_API, 1L);

        assertEquals("IN_TRANSIT", outcome.getShipmentStatus(),
                "到港只是里程碑，不等于送达，更不等于 FBA 已入库");
    }

    @Test
    @DisplayName("回归：OUT_FOR_DELIVERY（派送中）不得被当作已送达")
    void testOutForDeliveryIsNotDelivered() {
        Shipment shipment = shipment(10L, "SHP-1", "IN_TRANSIT");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));
        when(trackingEventMapper.selectList(any())).thenReturn(Collections.emptyList());

        IngestOutcome outcome = ingestService.ingest("SHP-1", "TRK-1",
                List.of(event("派送中", "2026-08-20 10:00:00")),
                TrackingIngestService.SOURCE_API, 1L);

        assertEquals("IN_TRANSIT", outcome.getShipmentStatus(), "派送中可以失败，不能提前算作送达");
    }

    @Test
    @DisplayName("承运商确认送达 → DELIVERED（不直接跳到 FBA 已入库）")
    void testDeliveredMapsToDelivered() {
        Shipment shipment = shipment(10L, "SHP-1", "IN_TRANSIT");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));
        when(trackingEventMapper.selectList(any())).thenReturn(Collections.emptyList());

        IngestOutcome outcome = ingestService.ingest("SHP-1", "TRK-1",
                List.of(event("已签收", "2026-08-20 10:00:00")),
                TrackingIngestService.SOURCE_API, 1L);

        assertEquals("DELIVERED", outcome.getShipmentStatus(),
                "FBA 是否入库由入库渠道提供，不能由承运商签收推断");
    }

    @Test
    @DisplayName("已判定延误的货件不因普通轨迹点回退为在途")
    void testDelayedNotRegressedByPlainEvent() {
        Shipment shipment = shipment(10L, "SHP-1", "DELAYED");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));
        when(trackingEventMapper.selectList(any())).thenReturn(Collections.emptyList());

        IngestOutcome outcome = ingestService.ingest("SHP-1", "TRK-1",
                List.of(event("运输中", "2026-08-20 10:00:00")),
                TrackingIngestService.SOURCE_API, 1L);

        assertFalse(outcome.isStatusChanged(), "延误是需要人工关注的信号，不应被普通轨迹抹平");
        assertEquals("DELAYED", shipment.getStatus());
        // 状态未变仍需落库：取数时间必须刷新
        verify(shipmentMapper).updateById(shipment);
    }

    // ================================================================ 延误判定

    @Test
    @DisplayName("ETA 超期 → 标记 DELAYED")
    void testMarkDelayedShipments() {
        Shipment overdue = shipment(10L, "SHP-1", "IN_TRANSIT");
        overdue.setEta(LocalDate.now().minusDays(10).format(DATE_FMT));
        when(shipmentMapper.selectList(any())).thenReturn(List.of(overdue));

        int marked = ingestService.markDelayedShipments(0);

        assertEquals(1, marked);
        assertEquals("DELAYED", overdue.getStatus());
        verify(shipmentMapper).updateById(overdue);
    }

    @Test
    @DisplayName("宽限期内的 ETA → 不判延误")
    void testMarkDelayedRespectsGraceDays() {
        Shipment justDue = shipment(11L, "SHP-2", "IN_TRANSIT");
        justDue.setEta(LocalDate.now().format(DATE_FMT));
        Shipment noEta = shipment(12L, "SHP-3", "IN_TRANSIT");
        when(shipmentMapper.selectList(any())).thenReturn(List.of(justDue, noEta));

        int marked = ingestService.markDelayedShipments(0);

        assertEquals(0, marked, "ETA 当天与未填 ETA 的都不应被判为延误");
        verify(shipmentMapper, never()).updateById(any(Shipment.class));
    }

    @Test
    @DisplayName("已标延误的货件 → 重复标记不重复计数")
    void testMarkDelayedSkipsAlreadyMarked() {
        Shipment already = shipment(13L, "SHP-4", "DELAYED");
        already.setEta(LocalDate.now().minusDays(30).format(DATE_FMT));
        when(shipmentMapper.selectList(any())).thenReturn(List.of(already));

        assertEquals(0, ingestService.markDelayedShipments(0));
        verify(shipmentMapper, never()).updateById(any(Shipment.class));
    }

    // ================================================================ 测试夹具

    private static Shipment shipment(Long id, String shipmentNo, String status) {
        Shipment shipment = new Shipment();
        shipment.setId(id);
        shipment.setShipmentNo(shipmentNo);
        shipment.setMasterTrackingNo("TRK-1");
        shipment.setShopId(1L);
        shipment.setStatus(status);
        return shipment;
    }

    private static TrackingEvent event(String rawStatus, String eventTime) {
        TrackingEvent event = new TrackingEvent();
        event.setRawStatus(rawStatus);
        event.setEventStatus(rawStatus);
        event.setEventTime(eventTime);
        return event;
    }

    private static TrackingEvent existing(String eventStatus, String eventTime) {
        TrackingEvent event = new TrackingEvent();
        event.setEventStatus(eventStatus);
        event.setEventTime(eventTime);
        return event;
    }
}
