package com.amz.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三方物流轨迹 API 配置（取数入口 B）。
 * <p>
 * <b>默认全关：</b>{@link #enabled} 默认为 false，且未配置凭证时客户端不发起任何请求。
 * 这样做的原因很实际——跨境电商的物流聚合服务都是付费配额制，
 * 若默认开启，任何一次误部署都会持续消耗配额并可能触发对端风控；
 * 而「未配置就直接返回空轨迹」不会让业务崩掉，只是没有自动更新，
 * 使用者仍可通过入口 A（外部导入）维护数据。
 * <p>
 * <b>凭证来源：</b>{@link #apiKey} 一律从环境变量注入（如 {@code AMZ_17TRACK_KEY}），
 * 不写入仓库配置文件。
 */
@Data
@Component
@ConfigurationProperties(prefix = "amz.logistics.tracking")
public class LogisticsTrackingProperties {

    /** 总开关。关闭时客户端不发起外部请求，直接返回空轨迹 */
    private boolean enabled = false;

    /** API 基址 */
    private String baseUrl = "https://api.17track.net";

    /**
     * 单号查询路径。
     * <p>
     * 独立成配置项而非硬编码：17track 的版本路径（v2.1 / v2.2 / v2.4）随其迭代变动，
     * 对端升级时改配置即可，无需重新构建。
     */
    private String queryPath = "/track/v2.2/gettrackinfo";

    /** API 凭证（17track 的 17token），由环境变量注入 */
    private String apiKey;

    /** 凭证所在的请求头名 */
    private String apiKeyHeader = "17token";

    /**
     * 承运商 → 17track 承运商代码 的映射。
     * <p>
     * 系统内承运商写的是名称（COSCO / Maersk / DHL），而 17track 要求数字代码（如 COSCO=1001）。
     * 未命中映射时不报错，而是<u>不带承运商代码查询</u>，交由 17track 自行识别单号归属——
     * 这样新增承运商时不会因漏配映射而完全查不到数据，只是可能因识别歧义而匹配较慢。
     */
    private Map<String, Integer> carrierCodes = new LinkedHashMap<>();

    /**
     * 单个运单解析出的轨迹点上限。
     * <p>
     * 用于防御异常响应：若对端返回畸形数据（如同一状态重复数千条），
     * 截断可避免一次调度把大量垃圾数据写入库中。
     */
    private int maxEventsPerShipment = 200;
}
