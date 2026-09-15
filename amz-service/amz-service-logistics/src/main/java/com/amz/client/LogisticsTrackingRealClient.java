package com.amz.client;

import com.amz.http.ResilientHttpClient;
import com.amz.model.TrackingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 物流轨迹查询真实客户端（取数入口 B，对接 17track 聚合查询）。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * <b>三层降级，保证「没有凭证也不影响系统运行」：</b>
 * <ol>
 *   <li>配置未开启 / 凭证缺失 → 直接返回空列表，<b>不发起任何外部请求</b>（不白白消耗配额与触发对端风控）</li>
 *   <li>对端返回错误码或响应畸形 → 记 error 日志并返回空列表</li>
 *   <li>网络异常 / 熔断打开 → 由 {@link ResilientHttpClient} 负责重试与快速失败，异常在此层收敛为空列表</li>
 * </ol>
 * 之所以一律降级为「空轨迹」而不向上抛异常：轨迹更新是<b>可延迟的后台增益</b>，
 * 一次对端抖动不该让整个货件同步流程报错——那会把「暂时没更新」变成「同步失败」，
 * 让运维去追一个无须处理的告警。货件在没有新轨迹时保持原状态，语义上也是正确的。
 * <p>
 * <b>本层不做状态映射与时间归一化：</b>只搬运承运商原文（{@code stage}/描述/原始时间），
 * 交由落库核心统一处理。否则 17track 与外部导入两条入口会各自解出不同状态，
 * 同一批数据换个入口进来就变了口径。
 */
@Slf4j
@Component
@Profile("!mock")
public class LogisticsTrackingRealClient implements LogisticsTrackingClient {

    /**
     * 熔断与指标的隔离维度。
     * <p>
     * 按「外部系统」而非「接口」划分，才能让对端整体不可用时一次性熔断所有调用，
     * 而不是逐个接口慢速失败。
     */
    private static final String TARGET = "17TRACK";

    /**
     * 仅供本类构造请求树与解析响应树使用的 ObjectMapper。
     * <p>
     * 刻意不注入容器内的 ObjectMapper：本类只做树模型读写、不做 POJO 绑定，
     * 容器级配置（null 字段剔除、未知字段策略等）在此既不需要也不应生效。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 统一出站通道（含超时/重试/熔断/指标）。
     * <p>
     * 声明为非必需依赖：该 Bean 由 amz-common 自动配置按「servlet 应用」条件注册，
     * 若在其他形态（如测试切片、非 Web 上下文）下缺席，本类应退化为「不可用」而非启动失败。
     */
    @Autowired(required = false)
    private ResilientHttpClient httpClient;

    @Autowired
    private LogisticsTrackingProperties properties;

    @Override
    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    @Override
    public List<TrackingEvent> queryTracking(String trackingNo, String carrier) {
        String reason = unavailableReason();
        if (reason != null) {
            log.warn("物流轨迹 API 不可用（{}），跳过查询：trackingNo={} carrier={}", reason, trackingNo, carrier);
            return Collections.emptyList();
        }
        if (!StringUtils.hasText(trackingNo)) {
            log.warn("运单号为空，跳过物流轨迹查询：carrier={}", carrier);
            return Collections.emptyList();
        }

        try {
            String url = buildQueryUrl();
            Map<String, String> headers = new HashMap<>(2);
            headers.put(properties.getApiKeyHeader(), properties.getApiKey());
            String response = httpClient.post(TARGET, url, headers, buildRequestBody(trackingNo, carrier));
            return parseEvents(response, trackingNo);
        } catch (Exception e) {
            // 含网络异常、熔断打开、JSON 解析异常：统一收敛为空列表，
            // 由下一次调度自然重试（轨迹按指纹幂等合并，重试无副作用）
            log.error("17track 轨迹查询失败，本次跳过（下次调度将重试）：trackingNo={} carrier={} err={}",
                    trackingNo, carrier, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ 请求构造

    /** 拼接查询地址，容忍 baseUrl 结尾多余的斜杠 */
    private String buildQueryUrl() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = properties.getQueryPath() == null ? "" : properties.getQueryPath().trim();
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    /**
     * 构造查询请求体：{@code [{"number":"COSU1234567","carrier":1001}]}。
     * <p>
     * 17track 的查询接口接受数组（单次可带多个单号）。此处每次只放一个，
     * 是为了让「哪个运单查失败」与「哪个运单有更新」能一一对应；
     * 若后续遇到配额压力，可在不改接口签名的前提下改为批量聚合调用。
     * 用 Jackson 构造而非字符串拼接，避免运单号中的特殊字符破坏 JSON 结构。
     */
    private String buildRequestBody(String trackingNo, String carrier) throws Exception {
        ArrayNode root = objectMapper.createArrayNode();
        ObjectNode item = root.addObject();
        item.put("number", trackingNo.trim());
        Integer carrierCode = resolveCarrierCode(carrier);
        if (carrierCode != null) {
            item.put("carrier", carrierCode);
        }
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 承运商名称 → 17track 数字代码。
     * <p>
     * 未命中时返回 null（请求不带 carrier 字段），交由 17track 按单号特征自动识别——
     * 这样新增承运商不会因漏配映射而完全查不到数据。
     */
    private Integer resolveCarrierCode(String carrier) {
        if (!StringUtils.hasText(carrier) || properties.getCarrierCodes() == null) {
            return null;
        }
        Integer code = properties.getCarrierCodes().get(carrier.trim().toUpperCase(Locale.ROOT));
        if (code == null) {
            log.debug("承运商未配置 17track 代码，将由其自动识别：carrier={}", carrier);
        }
        return code;
    }

    // ------------------------------------------------------------------ 响应解析

    /**
     * 解析 17track 响应为轨迹点列表。
     * <p>
     * 用树模型而非固定 DTO：该接口版本间的字段层级有过调整（如 providers 的嵌套深度），
     * 树模型配合 {@code path()} 的空安全语义，能让单点字段变更退化为「少解析几个字段」，
     * 而不是「整体反序列化失败、一条轨迹都拿不到」。
     */
    private List<TrackingEvent> parseEvents(String response, String trackingNo) throws Exception {
        JsonNode root = objectMapper.readTree(response);

        int code = root.path("code").asInt(Integer.MIN_VALUE);
        if (code != 0) {
            // 非 0 多为鉴权失效或配额耗尽 —— 必须显式暴露，否则表现为「物流一直不更新」，
            // 排查时会先怀疑承运商而找错方向
            String message = firstText(root.path("data"), "message");
            if (message == null) {
                message = firstText(root, "message");
            }
            log.error("17track 返回错误码：trackingNo={} code={} message={}", trackingNo, code, message);
            return Collections.emptyList();
        }

        logRejected(root.path("data").path("rejected"), trackingNo);

        JsonNode accepted = root.path("data").path("accepted");
        if (!accepted.isArray()) {
            log.warn("17track 响应缺少 accepted 节点：trackingNo={} body={}", trackingNo, abbreviate(response));
            return Collections.emptyList();
        }

        List<TrackingEvent> events = new ArrayList<>();
        for (JsonNode item : accepted) {
            extractFromItem(item, events);
        }

        int max = properties.getMaxEventsPerShipment();
        if (max > 0 && events.size() > max) {
            log.warn("17track 返回轨迹点数超上限，已截断：trackingNo={} 实际={} 上限={}", trackingNo, events.size(), max);
            return new ArrayList<>(events.subList(0, max));
        }
        if (events.isEmpty()) {
            log.info("17track 查询成功但暂无轨迹：trackingNo={}", trackingNo);
        }
        return events;
    }

    /**
     * 记录被拒运单。
     * <p>
     * 17track 对「单号格式不合法 / 已超出跟踪期限 / 配额不足」等情形仍返回 HTTP 200，
     * 只是把该单号放进 {@code rejected}。若不读取该节点，调用方只会看到一条空轨迹，
     * 进而误判为「承运商还没更新」——这是最容易误导排查方向的一类静默失败。
     */
    private void logRejected(JsonNode rejected, String trackingNo) {
        if (!rejected.isArray() || rejected.isEmpty()) {
            return;
        }
        for (JsonNode item : rejected) {
            log.error("17track 拒绝该运单：query={} number={} errorCode={} errorMessage={}",
                    trackingNo, firstText(item, "number"),
                    firstText(item.path("error"), "code"), firstText(item.path("error"), "message"));
        }
    }

    /**
     * 从单个 accepted 条目中提取轨迹点。
     * <p>
     * 合并该运单下<b>所有</b> providers 的事件，而不是只取第一个：
     * 头程链路常由起运国与目的国两家承运商接力供数，只取一家会丢掉半条链路
     * （典型表现是「到港」之后直接断掉，看不到派送与签收）。
     * 重复事件由落库核心按 (状态, 时间) 指纹合并，因此合并不会产生脏数据。
     */
    private void extractFromItem(JsonNode item, List<TrackingEvent> sink) {
        JsonNode providers = item.path("track_info").path("tracking").path("providers");
        if (!providers.isArray()) {
            return;
        }
        for (JsonNode provider : providers) {
            JsonNode events = provider.path("events");
            if (!events.isArray()) {
                continue;
            }
            for (JsonNode event : events) {
                TrackingEvent converted = toTrackingEvent(event);
                if (converted != null) {
                    sink.add(converted);
                }
            }
        }
    }

    /**
     * 单条 17track 事件 → 内部轨迹点。
     * <p>
     * 只填原始字段，不做映射与换算：
     * <ul>
     *   <li>{@code stage} 是 17track 归一化后的状态枚举（InTransit / Delivered / Undelivered 等），
     *       映射准确度高于自由文本描述，故优先作为状态来源；</li>
     *   <li>时间原样传出（可能是带时区偏移的 {@code time_iso}），
     *       由落库核心统一归一为 UTC 后再比较——若在此各自处理时区，
     *       不同承运商的不同偏移会把「最新事件」判错。</li>
     * </ul>
     */
    private TrackingEvent toTrackingEvent(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String stage = firstText(node, "stage");
        String description = firstText(node, "description");
        String subStatus = firstText(node, "sub_status");
        String time = firstText(node, "time_iso", "time_utc", "time_raw");
        String location = firstText(node, "location", "address");

        // stage 优先，其次自由描述，最后退化到 sub_status；
        // 三者皆缺则该条无法参与状态映射，丢弃（仅有时间不构成有意义的轨迹点）
        String primary = stage != null ? stage : (description != null ? description : subStatus);
        if (primary == null) {
            return null;
        }

        TrackingEvent event = new TrackingEvent();
        event.setEventStatus(primary);
        event.setRawStatus(primary);
        event.setDescription(description != null ? description : subStatus);
        event.setLocation(location);
        event.setEventTime(time);
        // 17track 不返回经纬度：轨迹可视化按地点文字展示，
        // 不做地理编码猜测，以免把错误坐标画到看板上
        return event;
    }

    // ------------------------------------------------------------------ 通用工具

    /** 返回第一个非空字段值，全部为空时返回 null */
    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String text = value.asText();
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
        }
        return null;
    }

    /** 截断响应体用于日志，避免一次异常把整页错误页灌进日志 */
    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...(truncated)";
    }

    /**
     * 判断不可用原因；可用时返回 null。
     * <p>
     * 区分「未开启」与「缺凭证」：前者是预期配置，后者是部署遗漏，
     * 两者运维含义完全不同，日志里必须能区分。
     */
    private String unavailableReason() {
        if (properties == null || !properties.isEnabled()) {
            return "未开启，amz.logistics.tracking.enabled=false";
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            return "缺少凭证 amz.logistics.tracking.api-key";
        }
        if (httpClient == null) {
            return "统一出站客户端未装配";
        }
        return null;
    }
}
