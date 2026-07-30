package com.amz.client;

import com.amz.model.TrackingEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 物流轨迹查询模拟客户端。
 * <p>
 * 离线模拟实现，返回构造的轨迹链路。
 * 仅在 {@code spring.profiles.active=mock} 时生效。
 */
@Slf4j
@Component
@Profile("mock")
public class LogisticsTrackingMockClient implements LogisticsTrackingClient {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public List<TrackingEvent> queryTracking(String trackingNo, String carrier) {
        log.info("物流轨迹查询模拟：trackingNo={} carrier={}", trackingNo, carrier);
        List<TrackingEvent> events = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 模拟一条完整的头程物流轨迹链路
        events.add(buildEvent(trackingNo, "DELIVERED", "FBA-LAX9 仓库, CA, USA",
                "货物已送达 FBA 仓库", now.minusDays(1), -118.27, 33.94));
        events.add(buildEvent(trackingNo, "OUT_FOR_DELIVERY", "洛杉矶, CA, USA",
                "派送中：前往 FBA-LAX9", now.minusDays(2), -118.24, 34.05));
        events.add(buildEvent(trackingNo, "CUSTOMS_CLEARANCE", "洛杉矶港, CA, USA",
                "清关完成", now.minusDays(4), -118.27, 33.94));
        events.add(buildEvent(trackingNo, "ARRIVED", "洛杉矶港, CA, USA",
                "货船已抵港", now.minusDays(5), -118.27, 33.94));
        events.add(buildEvent(trackingNo, "IN_TRANSIT", "太平洋",
                "海运中：预计 7 天后抵港", now.minusDays(15), -160.0, 30.0));
        events.add(buildEvent(trackingNo, "DEPARTED", "深圳港, 中国",
                "货船已起航", now.minusDays(20), 113.88, 22.54));
        events.add(buildEvent(trackingNo, "CREATED", "深圳工厂, 中国",
                "货物已入库，等待发货", now.minusDays(22), 114.05, 22.55));
        return events;
    }

    private TrackingEvent buildEvent(String shipmentNo, String status, String location,
                                     String desc, LocalDateTime time, double lon, double lat) {
        TrackingEvent e = new TrackingEvent();
        e.setEventStatus(status);
        e.setLocation(location);
        e.setDescription(desc);
        e.setEventTime(time.format(FMT));
        e.setLongitude(lon);
        e.setLatitude(lat);
        return e;
    }
}
