package com.amz.outbox;

import com.amz.connector.ErrorSummary;
import com.amz.connector.RestrictedResource;
import com.amz.connector.SpApiCallException;
import com.amz.connector.SpApiTokenSource;
import com.amz.mapper.SpApiCallOutboxMapper;
import com.amz.model.SpApiCallOutboxEntity;
import com.amz.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * SP-API 持久化调用账本。
 * <p>
 * 语义边界：
 * <ul>
 *   <li>{@code begin} 必须在任何 LWA/SP-API 网络请求之前成功；数据库不可用时不发请求，避免无审计调用；</li>
 *   <li>请求体与响应体加密落库，错误文本只保存已脱敏摘要；</li>
 *   <li>429、5xx、传输失败可重试；401/403、4xx、参数错误与本地异常进入 DLQ；</li>
 *   <li>重放通过原子状态迁移领取记录，多实例不会同时重放同一行。</li>
 * </ul>
 */
@Service
public class SpApiCallOutboxService {

    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String REPLAYING = "REPLAYING";
    public static final String REPLAYED = "REPLAYED";
    public static final String DLQ = "DLQ";

    private static final Set<String> OUTBOX_STATUSES = Set.of(
            PENDING, SUCCEEDED, FAILED, REPLAYING, REPLAYED, DLQ);

    private static final int ERROR_MESSAGE_LIMIT = 4000;

    private final SpApiCallOutboxMapper mapper;
    private final CryptoUtil cryptoUtil;
    private final int maxAttempts;
    private final long baseDelaySeconds;

    public SpApiCallOutboxService(SpApiCallOutboxMapper mapper,
                                  CryptoUtil cryptoUtil,
                                  @Value("${spapi.outbox.max-attempts:4}") int maxAttempts,
                                  @Value("${spapi.outbox.base-delay-seconds:60}") long baseDelaySeconds) {
        this.mapper = mapper;
        this.cryptoUtil = cryptoUtil;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelaySeconds = Math.max(1L, baseDelaySeconds);
    }

    /** 在发起网络请求前创建一条 PENDING 记录，返回 Outbox ID。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long begin(CallRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("call request is required");
        }
        List<Integer> expectedStatuses = normalizeExpectedStatuses(request.expectedStatuses(), null);
        SpApiTokenSource tokenSource = SpApiTokenSource.fromPersisted(
                request.tokenSource() == null ? null : request.tokenSource().name());
        List<RestrictedResource> restrictedResources =
                normalizeRestrictedResources(tokenSource, request.restrictedResources());
        LocalDateTime now = LocalDateTime.now();
        SpApiCallOutboxEntity entity = new SpApiCallOutboxEntity();
        entity.setShopId(request.shopId());
        entity.setMarketplaceId(request.marketplaceId());
        entity.setOperationId(request.operationId());
        entity.setHttpMethod(request.httpMethod());
        entity.setRequestPath(request.path());
        entity.setRequestQuery(request.query());
        entity.setRequestBodyEncrypted(cryptoUtil.encrypt(request.body()));
        entity.setExpectedStatus(expectedStatuses.get(0));
        entity.setExpectedStatuses(formatExpectedStatuses(expectedStatuses));
        entity.setRateLimitVariant(normalizeVariant(request.rateLimitVariant()));
        entity.setIdempotencyKey(request.idempotencyKey());
        entity.setReplayOfId(request.replayOfId());
        entity.setTokenSource(tokenSource.name());
        if (tokenSource == SpApiTokenSource.RDT) {
            entity.setRestrictedResourcesEncrypted(
                    cryptoUtil.encrypt(serializeRestrictedResources(restrictedResources)));
            entity.setRestrictedResourceHash(RestrictedResource.canonicalKey(restrictedResources));
            entity.setRestrictedResourceCount(restrictedResources.size());
        }
        entity.setStatus(PENDING);
        entity.setAttemptCount(0);
        entity.setMaxAttempts(maxAttempts);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("SP-API Outbox 插入后未返回主键，拒绝继续出站调用");
        }
        return entity.getId();
    }
    /**
     * 原子地把已领取的原记录标记为 REPLAYED，并插入一条新的 PENDING 重放记录。
     * <p>
     * 原记录只表示“已经派生重放任务”，实际成功/失败由新记录承载。这样即使新调用失败，
     * 也不会丢失原始失败证据；插入失败时事务回滚，原记录仍保持 REPLAYING 供上层释放。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createReplay(ReplayCall replay) {
        if (replay == null || replay.id() == null) {
            throw new IllegalArgumentException("replay call and original id are required");
        }
        List<Integer> expectedStatuses = normalizeExpectedStatuses(replay.expectedStatuses(), null);
        SpApiTokenSource tokenSource = SpApiTokenSource.fromPersisted(
                replay.tokenSource() == null ? null : replay.tokenSource().name());
        List<RestrictedResource> restrictedResources =
                normalizeRestrictedResources(tokenSource, replay.restrictedResources());
        LocalDateTime now = LocalDateTime.now();

        UpdateWrapper<SpApiCallOutboxEntity> claim = new UpdateWrapper<>();
        claim.eq("id", replay.id())
                .eq("status", REPLAYING)
                .set("status", REPLAYED)
                .set("next_attempt_at", null)
                .set("completed_at", now)
                .set("updated_at", now);
        if (mapper.update(null, claim) != 1) {
            throw new IllegalStateException("Outbox replay claim lost: id=" + replay.id());
        }

        SpApiCallOutboxEntity entity = new SpApiCallOutboxEntity();
        entity.setShopId(replay.shopId());
        entity.setMarketplaceId(replay.marketplaceId());
        entity.setOperationId(replay.operationId());
        entity.setHttpMethod(replay.httpMethod());
        entity.setRequestPath(replay.path());
        entity.setRequestQuery(replay.query());
        entity.setRequestBodyEncrypted(cryptoUtil.encrypt(replay.body()));
        entity.setExpectedStatus(expectedStatuses.get(0));
        entity.setExpectedStatuses(formatExpectedStatuses(expectedStatuses));
        entity.setRateLimitVariant(normalizeVariant(replay.rateLimitVariant()));
        entity.setIdempotencyKey(isWrite(replay.httpMethod()) ? UUID.randomUUID().toString() : null);
        entity.setReplayOfId(replay.id());
        entity.setTokenSource(tokenSource.name());
        if (tokenSource == SpApiTokenSource.RDT) {
            entity.setRestrictedResourcesEncrypted(
                    cryptoUtil.encrypt(serializeRestrictedResources(restrictedResources)));
            entity.setRestrictedResourceHash(RestrictedResource.canonicalKey(restrictedResources));
            entity.setRestrictedResourceCount(restrictedResources.size());
        }
        entity.setStatus(PENDING);
        entity.setAttemptCount(0);
        entity.setMaxAttempts(maxAttempts);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("SP-API Outbox 重放记录插入后未返回主键，拒绝继续出站调用");
        }
        return entity.getId();
    }
    /** 记录一次成功调用及其加密响应体。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long id, HttpResponse<?> response) {
        SpApiCallOutboxEntity entity = require(id);
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(SUCCEEDED);
        entity.setAttemptCount(value(entity.getAttemptCount()) + 1);
        entity.setResponseStatus(response == null ? null : response.statusCode());
        entity.setResponseBodyEncrypted(response == null ? null : cryptoUtil.encrypt(body(response)));
        entity.setResponseRequestId(response == null ? null : requestId(response));
        entity.setNextAttemptAt(null);
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
    }

    /** 记录最终失败；可重试且未耗尽时进入 FAILED，否则进入 DLQ。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String fail(Long id, SpApiCallException error, String rawResponseBody) {
        SpApiCallOutboxEntity entity = require(id);
        int attempts = value(entity.getAttemptCount()) + 1;
        int limit = entity.getMaxAttempts() == null ? maxAttempts : entity.getMaxAttempts();
        boolean retryable = isRetryable(error.getPlatformStatus());
        boolean exhausted = attempts >= limit;
        boolean canRetry = retryable && !exhausted;
        LocalDateTime now = LocalDateTime.now();

        entity.setAttemptCount(attempts);
        entity.setStatus(canRetry ? FAILED : DLQ);
        entity.setNextAttemptAt(canRetry ? now.plusSeconds(backoffSeconds(attempts)) : null);
        entity.setLastErrorCode(errorCode(error));
        entity.setLastErrorMessage(truncate(ErrorSummary.sanitize(error.getDiagnostic())));
        entity.setResponseStatus(error.getPlatformStatus() > 0 ? error.getPlatformStatus() : null);
        entity.setResponseBodyEncrypted(cryptoUtil.encrypt(rawResponseBody));
        entity.setResponseRequestId(error.getRequestId());
        entity.setCompletedAt(canRetry ? null : now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
        return entity.getStatus();
    }

    /** 原子领取一条 FAILED/DLQ 记录用于重放。返回 1 表示领取成功。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int claimForReplay(Long id) {
        UpdateWrapper<SpApiCallOutboxEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .in("status", FAILED, DLQ)
                .set("status", REPLAYING)
                .set("updated_at", LocalDateTime.now());
        return mapper.update(null, wrapper);
    }

    /** 新调用记录已经创建后，将原记录标记为 REPLAYED，形成可审计链。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReplayed(Long id) {
        SpApiCallOutboxEntity entity = require(id);
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(REPLAYED);
        entity.setNextAttemptAt(null);
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
    }

    /**
     * 重放尚未创建新记录时释放领取状态，避免记录永久卡在 REPLAYING。
     * <p>
     * 释放后进入 DLQ 而不是 FAILED：领取动作本身可能来自人工重放，不能因为一次领取失败
     * 就把原本不可自动重试的写操作重新丢回自动调度队列。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseReplayClaim(Long id) {
        UpdateWrapper<SpApiCallOutboxEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .eq("status", REPLAYING)
                .set("status", DLQ)
                .set("next_attempt_at", null)
                .set("updated_at", LocalDateTime.now());
        mapper.update(null, wrapper);
    }

    /** 是否存在由原记录派生的重放记录。 */
    public boolean hasReplayRecord(Long originalId) {
        LambdaQueryWrapper<SpApiCallOutboxEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SpApiCallOutboxEntity::getReplayOfId, originalId);
        return mapper.selectCount(wrapper) > 0;
    }

    /** 读取待重放记录并解密请求体。 */
    public ReplayCall loadForReplay(Long id) {
        SpApiCallOutboxEntity entity = require(id);
        if (!FAILED.equals(entity.getStatus()) && !DLQ.equals(entity.getStatus())
                && !REPLAYING.equals(entity.getStatus())) {
            throw new IllegalStateException("Outbox 记录不可重放：id=" + id + " status=" + entity.getStatus());
        }
        SpApiTokenSource tokenSource = SpApiTokenSource.fromPersisted(entity.getTokenSource());
        List<RestrictedResource> restrictedResources = loadRestrictedResources(entity, tokenSource);
        return new ReplayCall(entity.getId(), entity.getShopId(), entity.getMarketplaceId(),
                entity.getOperationId(), entity.getHttpMethod(), entity.getRequestPath(),
                entity.getRequestQuery(), cryptoUtil.decrypt(entity.getRequestBodyEncrypted()),
                normalizeExpectedStatuses(parseExpectedStatuses(entity.getExpectedStatuses()),
                        entity.getExpectedStatus()),
                entity.getRateLimitVariant(), tokenSource, restrictedResources,
                entity.getStatus());
    }
    /** 到期可自动重放的记录 ID，按创建顺序返回。 */
    public List<Long> dueIds(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<SpApiCallOutboxEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SpApiCallOutboxEntity::getStatus, FAILED)
                .le(SpApiCallOutboxEntity::getNextAttemptAt, LocalDateTime.now())
                .orderByAsc(SpApiCallOutboxEntity::getId)
                .last("LIMIT " + bounded);
        return mapper.selectList(wrapper).stream().map(SpApiCallOutboxEntity::getId).toList();
    }

    /**
     * 把长时间停留在 REPLAYING 的记录移入 DLQ。
     * <p>
     * 典型来源是实例在“领取成功、创建重放记录”之间崩溃。自动重置为 FAILED 会重复执行，
     * 因此这里选择人工可见的 DLQ；GET 重放也需要人工确认，不以可用性换取潜在重复副作用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStaleReplayClaims(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        UpdateWrapper<SpApiCallOutboxEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("status", REPLAYING)
                .lt("updated_at", cutoff)
                .set("status", DLQ)
                .set("next_attempt_at", null)
                .set("updated_at", LocalDateTime.now());
        return mapper.update(null, wrapper);
    }

    /** 安全列表视图：不返回请求/响应正文与密文。 */
    public List<OutboxView> list(String status, int limit) {
        return list(status, limit, null);
    }

    /**
     * 按调用方授权店铺集合下推过滤，避免先按全库 limit 截断后再过滤造成漏记录。
     *
     * @param allowedShopIds null/空表示内部或管理调用不限制店铺；非空表示只查询这些店铺
     */
    public List<OutboxView> list(String status, int limit, Collection<Long> allowedShopIds) {
        int bounded = Math.max(1, Math.min(limit, 200));
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<SpApiCallOutboxEntity> wrapper = new LambdaQueryWrapper<>();
        if (normalizedStatus != null) {
            wrapper.eq(SpApiCallOutboxEntity::getStatus, normalizedStatus);
        }
        if (allowedShopIds != null && !allowedShopIds.isEmpty()) {
            List<Long> shopIds = allowedShopIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (shopIds.isEmpty()) {
                return List.of();
            }
            wrapper.in(SpApiCallOutboxEntity::getShopId, shopIds);
        }
        wrapper.orderByDesc(SpApiCallOutboxEntity::getId).last("LIMIT " + bounded);
        return mapper.selectList(wrapper).stream().map(OutboxView::from).toList();
    }

    /** 读取单条安全视图；不存在时显式失败，不返回 null 伪装成空记录。 */
    public OutboxView view(Long id) {
        return OutboxView.from(require(id));
    }

    private SpApiCallOutboxEntity require(Long id) {
        SpApiCallOutboxEntity entity = id == null ? null : mapper.selectById(id);
        if (entity == null) {
            throw new IllegalStateException("SP-API Outbox 记录不存在：id=" + id);
        }
        return entity;
    }

    private static boolean isRetryable(int status) {
        return status == -1 || status == 429 || (status >= 500 && status <= 599);
    }

    private long backoffSeconds(int attempts) {
        int shift = Math.min(Math.max(0, attempts - 1), 8);
        return Math.min(TimeUnitCap.SECONDS, baseDelaySeconds * (1L << shift));
    }

    private static String errorCode(SpApiCallException error) {
        if (error.getPlatformCode() != null && !error.getPlatformCode().isBlank()) {
            return truncate(error.getPlatformCode(), 128);
        }
        if (error.getPlatformStatus() == -1) {
            return "TRANSPORT_ERROR";
        }
        if (error.getPlatformStatus() == 0) {
            return "LOCAL_ERROR";
        }
        return "HTTP_" + error.getPlatformStatus();
    }

    private static String body(HttpResponse<?> response) {
        if (response == null || response.body() == null) {
            return null;
        }
        return String.valueOf(response.body());
    }

    private static String requestId(HttpResponse<?> response) {
        for (String name : new String[]{"x-amzn-RequestId", "x-amzn-requestid", "x-amzn-request-id"}) {
            String value = response.headers().firstValue(name).orElse(null);
            if (value != null && !value.isBlank()) {
                return truncate(ErrorSummary.redact(value), 256);
            }
        }
        return null;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value) {
        return truncate(value, ERROR_MESSAGE_LIMIT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static List<Integer> normalizeExpectedStatuses(List<Integer> statuses, Integer fallback) {
        List<Integer> source = statuses;
        if (source == null || source.isEmpty()) {
            source = fallback == null ? List.of() : List.of(fallback);
        }
        if (source.isEmpty()) {
            throw new IllegalArgumentException("at least one expected SP-API status is required");
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (Integer status : source) {
            if (status == null || status < 100 || status > 599) {
                throw new IllegalArgumentException("invalid expected SP-API status: " + status);
            }
            normalized.add(status);
        }
        if (normalized.size() > 20) {
            throw new IllegalArgumentException("too many expected SP-API statuses");
        }
        return List.copyOf(normalized);
    }

    private static List<Integer> parseExpectedStatuses(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(",");
        java.util.ArrayList<Integer> statuses = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalStateException("invalid expected_statuses value: " + value);
            }
            try {
                statuses.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("invalid expected_statuses value: " + value, e);
            }
        }
        return statuses;
    }

    private static String formatExpectedStatuses(List<Integer> statuses) {
        return statuses.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<RestrictedResource> normalizeRestrictedResources(
            SpApiTokenSource tokenSource, List<RestrictedResource> resources) {
        if (tokenSource == null) {
            throw new IllegalArgumentException("SP-API token source is required");
        }
        return switch (tokenSource) {
            case LWA, LWA_GRANTLESS -> {
                if (resources != null && !resources.isEmpty()) {
                    throw new IllegalArgumentException(tokenSource
                            + " calls must not persist restricted resources");
                }
                yield List.of();
            }
            case RDT -> {
                if (resources == null || resources.isEmpty()) {
                    throw new IllegalArgumentException("RDT calls must persist restricted resources");
                }
                RestrictedResource.canonicalKey(resources);
                yield List.copyOf(resources);
            }
        };
    }
    private static String serializeRestrictedResources(List<RestrictedResource> resources) {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (RestrictedResource resource : resources) {
            JsonObject item = new JsonObject();
            item.addProperty("method", resource.method());
            item.addProperty("path", resource.path());
            if (!resource.dataElements().isEmpty()) {
                JsonArray dataElements = new JsonArray();
                resource.dataElements().forEach(dataElements::add);
                item.add("dataElements", dataElements);
            }
            items.add(item);
        }
        root.add("restrictedResources", items);
        return root.toString();
    }

    private static List<RestrictedResource> parseRestrictedResources(String json) {
        final JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("invalid persisted restricted resources", e);
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalStateException("invalid persisted restricted resources");
        }
        JsonElement element = root.getAsJsonObject().get("restrictedResources");
        if (element == null || !element.isJsonArray()) {
            throw new IllegalStateException("invalid persisted restricted resources");
        }
        JsonArray items = element.getAsJsonArray();
        if (items.isEmpty()) {
            throw new IllegalStateException("persisted restricted resources must not be empty");
        }
        java.util.ArrayList<RestrictedResource> resources = new java.util.ArrayList<>(items.size());
        for (JsonElement itemElement : items) {
            if (itemElement == null || !itemElement.isJsonObject()) {
                throw new IllegalStateException("invalid persisted restricted resource");
            }
            JsonObject item = itemElement.getAsJsonObject();
            String method = requiredString(item, "method");
            String path = requiredString(item, "path");
            List<String> dataElements = dataElements(item);
            resources.add(new RestrictedResource(method, path, dataElements));
        }
        RestrictedResource.canonicalKey(resources);
        return List.copyOf(resources);
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("invalid persisted restricted resource field: " + name);
        }
        return element.getAsString();
    }

    private static List<String> dataElements(JsonObject object) {
        JsonElement element = object.get("dataElements");
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            throw new IllegalStateException("invalid persisted restricted resource dataElements");
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || item.isJsonNull() || !item.isJsonPrimitive()
                    || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException("invalid persisted restricted resource dataElement");
            }
            values.add(item.getAsString());
        }
        return List.copyOf(values);
    }

    private List<RestrictedResource> loadRestrictedResources(SpApiCallOutboxEntity entity,
                                                             SpApiTokenSource tokenSource) {
        boolean hasMetadata = entity.getRestrictedResourcesEncrypted() != null
                || entity.getRestrictedResourceHash() != null
                || entity.getRestrictedResourceCount() != null;
        return switch (tokenSource) {
            case LWA, LWA_GRANTLESS -> {
                if (hasMetadata) {
                    throw new IllegalStateException(tokenSource
                            + " Outbox record contains restricted resource metadata");
                }
                yield List.of();
            }
            case RDT -> {
                if (entity.getRestrictedResourcesEncrypted() == null
                        || entity.getRestrictedResourcesEncrypted().isBlank()
                        || entity.getRestrictedResourceHash() == null
                        || entity.getRestrictedResourceHash().isBlank()
                        || entity.getRestrictedResourceCount() == null) {
                    throw new IllegalStateException(
                            "RDT Outbox record is missing restricted resource metadata");
                }
                List<RestrictedResource> resources = parseRestrictedResources(
                        cryptoUtil.decrypt(entity.getRestrictedResourcesEncrypted()));
                if (resources.size() != entity.getRestrictedResourceCount()) {
                    throw new IllegalStateException("persisted restricted resource count mismatch");
                }
                String actualHash = RestrictedResource.canonicalKey(resources);
                if (!actualHash.equalsIgnoreCase(entity.getRestrictedResourceHash())) {
                    throw new IllegalStateException("persisted restricted resource hash mismatch");
                }
                yield resources;
            }
        };
    }    private static String normalizeVariant(String variant) {
        return variant == null || variant.isBlank() ? null : variant.trim();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!OUTBOX_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported Outbox status: " + status);
        }
        return normalized;
    }

    private static boolean isWrite(String method) {
        return method != null && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
    }

    /** 调用方提交给 Outbox 的最小请求描述。 */
    public record CallRequest(Long shopId,
                              String marketplaceId,
                              String operationId,
                              String httpMethod,
                              String path,
                              String query,
                              String body,
                              List<Integer> expectedStatuses,
                              String rateLimitVariant,
                              String idempotencyKey,
                              Long replayOfId,
                              SpApiTokenSource tokenSource,
                              List<RestrictedResource> restrictedResources) {

        public static CallRequest of(Long shopId, String marketplaceId, String operationId,
                                     String httpMethod, String path, String query, String body,
                                     int expectedStatus) {
            return of(shopId, marketplaceId, operationId, httpMethod, path, query, body,
                    List.of(expectedStatus), null);
        }

        public static CallRequest of(Long shopId, String marketplaceId, String operationId,
                                     String httpMethod, String path, String query, String body,
                                     List<Integer> expectedStatuses, String rateLimitVariant) {
            return of(shopId, marketplaceId, operationId, httpMethod, path, query, body,
                    expectedStatuses, rateLimitVariant, SpApiTokenSource.LWA, List.of());
        }

        public static CallRequest of(Long shopId, String marketplaceId, String operationId,
                                     String httpMethod, String path, String query, String body,
                                     List<Integer> expectedStatuses, String rateLimitVariant,
                                     SpApiTokenSource tokenSource,
                                     List<RestrictedResource> restrictedResources) {
            String key = isWrite(httpMethod) ? UUID.randomUUID().toString() : null;
            return new CallRequest(shopId, marketplaceId, operationId, httpMethod, path,
                    query, body, expectedStatuses, rateLimitVariant, key, null,
                    tokenSource == null ? SpApiTokenSource.LWA : tokenSource,
                    restrictedResources == null ? List.of() : restrictedResources);
        }

        public CallRequest asReplay(Long originalId) {
            return new CallRequest(shopId, marketplaceId, operationId, httpMethod, path,
                    query, body, expectedStatuses, rateLimitVariant,
                    isWrite(httpMethod) ? UUID.randomUUID().toString() : null, originalId,
                    tokenSource, restrictedResources);
        }

        private static boolean isWrite(String method) {
            return method != null && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
        }
    }

    /** 重放所需的明文请求。仅用于服务内部执行，不得直接序列化到 API 响应。 */
    public record ReplayCall(Long id, Long shopId, String marketplaceId, String operationId,
                             String httpMethod, String path, String query, String body,
                             List<Integer> expectedStatuses, String rateLimitVariant,
                             SpApiTokenSource tokenSource,
                             List<RestrictedResource> restrictedResources,
                             String originalStatus) {
    }

    /** 对外安全视图。 */
    public record OutboxView(Long id, Long shopId, String operationId, String httpMethod,
                             String requestPath, String status, Integer attemptCount,
                             Integer maxAttempts, Integer responseStatus, String marketplaceId,
                             String responseRequestId,
                             String lastErrorCode, String lastErrorMessage,
                             LocalDateTime createdAt, LocalDateTime updatedAt,
                             LocalDateTime completedAt, List<Integer> expectedStatuses,
                             String rateLimitVariant, SpApiTokenSource tokenSource,
                             Integer restrictedResourceCount, String restrictedResourceHash) {

        public static OutboxView from(SpApiCallOutboxEntity entity) {
            return new OutboxView(entity.getId(), entity.getShopId(), entity.getOperationId(),
                    entity.getHttpMethod(), entity.getRequestPath(), entity.getStatus(),
                    entity.getAttemptCount(), entity.getMaxAttempts(), entity.getResponseStatus(),
                    entity.getMarketplaceId(), entity.getResponseRequestId(), entity.getLastErrorCode(),
                    entity.getLastErrorMessage(), entity.getCreatedAt(), entity.getUpdatedAt(),
                    entity.getCompletedAt(),
                    normalizeExpectedStatuses(parseExpectedStatuses(entity.getExpectedStatuses()),
                            entity.getExpectedStatus()),
                    entity.getRateLimitVariant(), SpApiTokenSource.fromPersisted(entity.getTokenSource()),
                    entity.getRestrictedResourceCount(), entity.getRestrictedResourceHash());
        }
    }

    /** 指数退避上限：24 小时。 */
    private static final class TimeUnitCap {
        private static final long SECONDS = 24L * 60L * 60L;

        private TimeUnitCap() {
        }
    }
}
