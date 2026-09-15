package com.amz.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 承运商状态文本 → 系统内部轨迹状态的映射器。
 * <p>
 * <b>为何单独抽一层：</b>外部导入与第三方 API 拿到的状态文本形态差异极大
 * （中文短句「清关中」「已开船」、英文枚举 {@code IN_TRANSIT}、承运商自有编码等），
 * 而系统内部只认固定的 {@link #INTERNAL_STATUSES} 集合。把映射集中在此处，
 * 后续按真实抓取样例补规则时无需改动落库逻辑。
 * <p>
 * <b>三层查找顺序：</b>
 * <ol>
 *   <li>入参 {@code eventStatusHint} 本身已是内部状态码 —— 直接采用（导入方可直接给标准值）</li>
 *   <li>外部配置 {@code amz.logistics.status-mapping} —— 承运商新增状态词时改配置即可，无需发版</li>
 *   <li>内置默认字典 + 关键词兜底</li>
 * </ol>
 * <b>兜底原则：永不丢弃。</b>无法识别时归入 {@code IN_TRANSIT} 并打 warn，
 * 因为轨迹点丢失比状态归类略粗更糟——前者会让看板的时效统计直接失真。
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "amz.logistics")
public class TrackingStatusMapper {

    /** 系统内部合法轨迹状态集合 */
    public static final Set<String> INTERNAL_STATUSES = Set.of(
            "CREATED", "DEPARTED", "IN_TRANSIT", "CUSTOMS_CLEARANCE",
            "ARRIVED", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION");

    /** 无法识别时的归属状态 */
    private static final String FALLBACK = "IN_TRANSIT";

    /** 内置默认字典：覆盖中英文常见状态词 */
    private static final Map<String, String> DEFAULTS = new HashMap<>();

    static {
        DEFAULTS.put("已创建", "CREATED");
        DEFAULTS.put("已下单", "CREATED");
        DEFAULTS.put("待发货", "CREATED");
        DEFAULTS.put("已揽收", "DEPARTED");
        DEFAULTS.put("已发货", "DEPARTED");
        DEFAULTS.put("已开船", "DEPARTED");
        DEFAULTS.put("已起飞", "DEPARTED");
        DEFAULTS.put("运输中", "IN_TRANSIT");
        DEFAULTS.put("在途", "IN_TRANSIT");
        DEFAULTS.put("清关中", "CUSTOMS_CLEARANCE");
        DEFAULTS.put("清关完成", "CUSTOMS_CLEARANCE");
        DEFAULTS.put("已到港", "ARRIVED");
        DEFAULTS.put("已到达", "ARRIVED");
        DEFAULTS.put("派送中", "OUT_FOR_DELIVERY");
        DEFAULTS.put("已签收", "DELIVERED");
        DEFAULTS.put("已送达", "DELIVERED");
        DEFAULTS.put("异常", "EXCEPTION");
        DEFAULTS.put("查验", "EXCEPTION");
        DEFAULTS.put("退件", "EXCEPTION");

        DEFAULTS.put("INFO RECEIVED", "CREATED");
        DEFAULTS.put("LABEL CREATED", "CREATED");
        DEFAULTS.put("PICKED UP", "DEPARTED");
        DEFAULTS.put("DEPARTED", "DEPARTED");
        DEFAULTS.put("DEPARTURE", "DEPARTED");
        DEFAULTS.put("IN TRANSIT", "IN_TRANSIT");
        DEFAULTS.put("IN_TRANSIT", "IN_TRANSIT");
        DEFAULTS.put("ARRIVED", "ARRIVED");
        DEFAULTS.put("CUSTOMS", "CUSTOMS_CLEARANCE");
        DEFAULTS.put("CUSTOMS CLEARANCE", "CUSTOMS_CLEARANCE");
        DEFAULTS.put("CUSTOMS_CLEARANCE", "CUSTOMS_CLEARANCE");
        DEFAULTS.put("CLEARED CUSTOMS", "CUSTOMS_CLEARANCE");
        DEFAULTS.put("OUT FOR DELIVERY", "OUT_FOR_DELIVERY");
        DEFAULTS.put("OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY");
        DEFAULTS.put("DELIVERED", "DELIVERED");
        DEFAULTS.put("EXCEPTION", "EXCEPTION");
        DEFAULTS.put("DELAYED", "EXCEPTION");
        DEFAULTS.put("RETURNED", "EXCEPTION");

        // 17track 的 stage 枚举（无空格连写形式，键需与其大写形态一致）。
        // 单独列举的原因：这些值若不显式登记，会落到关键词兜底，
        // 而 UNDELIVERED 含子串 DELIVERED，会被误判成「已签收」——
        // 投递失败被记成签收，异常告警与时效统计会同时失真。
        DEFAULTS.put("INFORECEIVED", "CREATED");
        DEFAULTS.put("INTRANSIT", "IN_TRANSIT");
        DEFAULTS.put("PICKUP", "DEPARTED");
        DEFAULTS.put("OUTFORDELIVERY", "OUT_FOR_DELIVERY");
        DEFAULTS.put("AVAILABLEFORPICKUP", "ARRIVED");
        DEFAULTS.put("UNDELIVERED", "EXCEPTION");
        DEFAULTS.put("DELIVERYFAILURE", "EXCEPTION");
        DEFAULTS.put("DELIVERYFAILED", "EXCEPTION");
        DEFAULTS.put("EXPIRED", "EXCEPTION");
        DEFAULTS.put("RETURNING", "EXCEPTION");
    }

    /** 外部配置覆盖项，来自 amz.logistics.status-mapping */
    private Map<String, String> statusMapping = new LinkedHashMap<>();

    public Map<String, String> getStatusMapping() {
        return statusMapping;
    }

    public void setStatusMapping(Map<String, String> statusMapping) {
        this.statusMapping = statusMapping == null ? new LinkedHashMap<>() : statusMapping;
    }

    /**
     * 将承运商状态文本映射为内部状态码。
     *
     * @param rawStatus       承运商原始状态文本，可为 null
     * @param eventStatusHint 调用方直接给出的状态值，若已是内部状态码则优先采用
     * @return 内部状态码，保证落在 {@link #INTERNAL_STATUSES} 内，永不为 null
     */
    public String toInternal(String rawStatus, String eventStatusHint) {
        String hint = normalizeUpper(eventStatusHint);
        if (hint != null && INTERNAL_STATUSES.contains(hint)) {
            return hint;
        }

        String raw = (rawStatus != null && !rawStatus.isBlank()) ? rawStatus : eventStatusHint;
        if (raw == null || raw.isBlank()) {
            return FALLBACK;
        }

        String original = raw.trim();
        String upper = original.toUpperCase(Locale.ROOT);

        String mapped = upperOrNull(statusMapping.get(upper));
        if (mapped == null) {
            mapped = upperOrNull(statusMapping.get(original));
        }
        if (mapped == null) {
            mapped = upperOrNull(DEFAULTS.get(upper));
        }
        if (mapped != null) {
            return mapped;
        }

        String keyword = keywordFallback(upper);
        if (keyword != null) {
            return keyword;
        }

        log.warn("承运商状态文本无法映射，按 {} 兜底（建议在 amz.logistics.status-mapping 补规则）：raw={}",
                FALLBACK, original);
        return FALLBACK;
    }

    /**
     * 关键词兜底：字典未命中时按子串判定。
     * <p>
     * 用于吸收「清关完成（已放行）」这类带修饰词的长句，避免为每种写法单列一条字典。
     * <p>
     * <b>判定顺序有约束：否定式必须先于肯定式。</b>「UNDELIVERED」包含子串「DELIVERED」，
     * 若先判肯定式，投递失败会被归类为「已签收」——这是最危险的一类误判：
     * 看板上异常件消失、时效统计反而变好，问题被数据掩盖而非暴露。
     * 因此这里把否定/失败语义整体前置，不依赖字典是否登记齐全。
     */
    private String keywordFallback(String upper) {
        if (containsAny(upper, "UNDELIVER", "NOT DELIVER", "DELIVERYFAIL", "DELIVERY FAIL",
                "FAILED ATTEMPT", "投递失败", "派送失败")) {
            return "EXCEPTION";
        }
        if (containsAny(upper, "OUT FOR DELIVERY", "派送", "投递")) {
            return "OUT_FOR_DELIVERY";
        }
        if (containsAny(upper, "DELIVERED", "签收", "已送达")) {
            return "DELIVERED";
        }
        if (containsAny(upper, "CUSTOMS", "清关", "报关")) {
            return "CUSTOMS_CLEARANCE";
        }
        if (containsAny(upper, "ARRIV", "到港", "抵达")) {
            return "ARRIVED";
        }
        if (containsAny(upper, "EXCEPTION", "异常", "查验", "扣关", "退件", "DELAY")) {
            return "EXCEPTION";
        }
        if (containsAny(upper, "DEPART", "开船", "起飞", "揽收", "起运")) {
            return "DEPARTED";
        }
        if (containsAny(upper, "TRANSIT", "在途", "运输中", "航行")) {
            return "IN_TRANSIT";
        }
        if (containsAny(upper, "CREATED", "已创建", "已下单", "入库")) {
            return "CREATED";
        }
        return null;
    }

    private boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeUpper(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** 仅当映射结果本身是合法内部状态码时才采纳，避免配置写错导致脏状态入库 */
    private String upperOrNull(String value) {
        String upper = normalizeUpper(value);
        return (upper != null && INTERNAL_STATUSES.contains(upper)) ? upper : null;
    }
}
