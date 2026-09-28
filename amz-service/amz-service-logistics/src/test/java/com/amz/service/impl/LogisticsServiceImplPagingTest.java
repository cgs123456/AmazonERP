package com.amz.service.impl;

import com.amz.client.LogisticsTrackingClient;
import com.amz.context.UserContext;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.ShipmentMapper;
import com.amz.mapper.TrackingEventMapper;
import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.TrackingIngestService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("物流列表与轨迹游标分页测试")
class LogisticsServiceImplPagingTest {

    @Mock
    private ShipmentMapper shipmentMapper;

    @Mock
    private TrackingEventMapper trackingEventMapper;

    @Mock
    private LogisticsTrackingClient trackingClient;

    @Mock
    private TrackingIngestService trackingIngestService;

    @InjectMocks
    private LogisticsServiceImpl service;

    @BeforeAll
    static void initMybatisTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Shipment.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), TrackingEvent.class);
    }

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(9);
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    @DisplayName("货件列表：探测行不返回，cursor 取本页最后一条可见行")
    void truncatedPageDropsProbeRowAndUsesLastVisibleCursor() {
        when(shipmentMapper.selectList(any())).thenReturn(List.of(
                shipment(40L), shipment(39L), shipment(38L)));

        PageResult<Shipment> page =
                service.listShipments(1L, "IN_TRANSIT", PageRequest.first(2));

        assertEquals(List.of(40L, 39L), page.items().stream().map(Shipment::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(39L), page.nextCursor());
    }

    @Test
    @DisplayName("货件列表：未取满一页时不误报截断")
    void untruncatedPageHasNoCursor() {
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment(2L)));

        PageResult<Shipment> page = service.listShipments(1L, null, PageRequest.first(50));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertNull(page.nextCursor());
    }

    @Test
    @DisplayName("货件列表：status 与 id 游标同时生效，并带显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cursorAndStatusUseKeysetWithExplicitLimit() {
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment(5L)));
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor(900L));

        service.listShipments(1L, "DELAYED", request);

        ArgumentCaptor<LambdaQueryWrapper<Shipment>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(shipmentMapper).selectList(captor.capture());
        LambdaQueryWrapper<Shipment> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT"), "货件列表必须禁止无界 selectList：" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(900L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("DELAYED"));
    }

    @Test
    @DisplayName("轨迹时间线：探测行不返回，非空时间游标使用 (eventTime,id)")
    void trackingTimelineDropsProbeAndUsesCompositeCursor() {
        when(shipmentMapper.selectById(99L)).thenReturn(shipment(99L));
        when(trackingEventMapper.selectList(any())).thenReturn(List.of(
                trackingEvent(51L, "2026-08-01T09:00:00"),
                trackingEvent(52L, "2026-08-01T10:00:00"),
                trackingEvent(53L, "2026-08-01T11:00:00")));

        PageResult<TrackingEvent> page =
                service.getTrackingTimeline(99L, PageRequest.first(2));

        assertEquals(List.of(51L, 52L), page.items().stream().map(TrackingEvent::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor("V|2026-08-01T10:00:00|52"), page.nextCursor());
    }

    @Test
    @DisplayName("轨迹时间线：空时间排在最后，游标只携带 id")
    void trackingTimelineNullTimeUsesNullCursor() {
        when(shipmentMapper.selectById(99L)).thenReturn(shipment(99L));
        when(trackingEventMapper.selectList(any())).thenReturn(List.of(
                trackingEvent(61L, null),
                trackingEvent(62L, null)));

        PageResult<TrackingEvent> page =
                service.getTrackingTimeline(99L, PageRequest.first(1));

        assertEquals(List.of(61L), page.items().stream().map(TrackingEvent::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor("N|61"), page.nextCursor());
    }

    @Test
    @DisplayName("轨迹时间线：非空时间游标按时间与 id 续扫，并显式 LIMIT")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void trackingTimelineNonNullCursorUsesKeysetWithExplicitLimit() {
        when(shipmentMapper.selectById(99L)).thenReturn(shipment(99L));
        when(trackingEventMapper.selectList(any())).thenReturn(List.of(
                trackingEvent(53L, "2026-08-01T11:00:00")));
        PageRequest request = PageRequest.of(10,
                PageRequest.encodeCursor("V|2026-08-01T10:00:00|52"));

        service.getTrackingTimeline(99L, request);

        ArgumentCaptor<LambdaQueryWrapper<TrackingEvent>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(trackingEventMapper).selectList(captor.capture());
        LambdaQueryWrapper<TrackingEvent> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("LIMIT 11"), "轨迹查询必须有显式 LIMIT：" + segment);
        assertTrue(segment.toUpperCase().contains("ORDER BY"), segment);
        assertTrue(segment.toUpperCase().contains("IS NULL"), segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("2026-08-01T10:00:00"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(52L));
    }

    @Test
    @DisplayName("轨迹时间线：空时间游标只在 NULL 段内按 id 续扫")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void trackingTimelineNullCursorScansNullSegment() {
        when(shipmentMapper.selectById(99L)).thenReturn(shipment(99L));
        when(trackingEventMapper.selectList(any())).thenReturn(List.of());
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor("N|61"));

        service.getTrackingTimeline(99L, request);

        ArgumentCaptor<LambdaQueryWrapper<TrackingEvent>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(trackingEventMapper).selectList(captor.capture());
        LambdaQueryWrapper<TrackingEvent> wrapper = captor.getValue();
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.toUpperCase().contains("IS NULL"), segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(61L));
        assertTrue(segment.contains("LIMIT 11"), segment);
    }

    @Test
    @DisplayName("轨迹时间线：非法游标 fail-closed，不得退回第一页")
    void trackingTimelineInvalidCursorFailsClosed() {
        when(shipmentMapper.selectById(99L)).thenReturn(shipment(99L));
        PageRequest request = PageRequest.of(10, PageRequest.encodeCursor("X|not-a-cursor"));

        assertThrows(InvalidParamException.class,
                () -> service.getTrackingTimeline(99L, request));

        verify(trackingEventMapper, never()).selectList(any());
    }

    private static Shipment shipment(Long id) {
        Shipment shipment = new Shipment();
        shipment.setId(id);
        shipment.setShopId(1L);
        shipment.setStatus("IN_TRANSIT");
        return shipment;
    }

    private static TrackingEvent trackingEvent(Long id, String eventTime) {
        TrackingEvent event = new TrackingEvent();
        event.setId(id);
        event.setShipmentId(99L);
        event.setEventTime(eventTime);
        event.setEventStatus("IN_TRANSIT");
        return event;
    }
}