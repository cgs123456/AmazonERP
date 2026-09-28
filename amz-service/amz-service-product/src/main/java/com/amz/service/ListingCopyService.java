package com.amz.service;

import com.amz.client.ListingsClient;
import com.amz.context.UserContext;
import com.amz.mapper.AmzProductMapper;
import com.amz.mapper.ListingCopyTaskMapper;
import com.amz.model.AmzProduct;
import com.amz.model.ListingCopyTask;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 跨站点 Listing 复制服务。
 * <p>
 * 流程：查源 Listing -> 创建任务(PENDING) -> 异步执行(翻译 -> 汇率换算 -> 加价 -> 提交 Feed)
 * -> 数据库登记 SUBMITTED -> 定时任务分次轮询处理状态。
 */
@Service
public class ListingCopyService {

    private static final Logger log = LoggerFactory.getLogger(ListingCopyService.class);

    /**
     * Marketplace ID -> {language, currency} 映射。
     */
    private static final Map<String, String[]> MARKETPLACE_MAP = Map.of(
            "ATVPDKIKX0DER", new String[]{"en", "USD"},  // US
            "A1F83G8C2ARO7P", new String[]{"en", "GBP"},  // UK
            "A1PA6795UKMFR9", new String[]{"de", "EUR"},  // DE
            "A13V1IB3VIYZZH", new String[]{"fr", "EUR"},  // FR
            "APJ6JRA9NG5V4",  new String[]{"it", "EUR"},  // IT
            "A1RKKUPIHCS9HS", new String[]{"es", "EUR"},  // ES
            "A1VC38T7YXB528", new String[]{"ja", "JPY"}   // JP
    );

    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_TIMEOUT = "TIMEOUT";
    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 15;
    private static final int DEFAULT_POLL_MAX_DURATION_SECONDS = 5 * 60;
    private static final int MAX_POLL_BATCH_SIZE = 100;
    /** 调度任务使用的最小权限系统身份；仅授予当前任务所属店铺。 */
    private static final int SYSTEM_USER_ID = 0;
    private static final String SYSTEM_ROLE = "VIEWER";

    @Autowired
    private AmzProductMapper amzProductMapper;

    @Autowired
    private ListingCopyTaskMapper listingCopyTaskMapper;

    @Autowired
    private TranslationService translationService;

    @Autowired
    private ExchangeRateService exchangeRateService;

    @Autowired
    private ListingsClient listingsClient;

    @Value("${listing-copy.poll.initial-delay-seconds:15}")
    private long initialDelaySeconds = DEFAULT_POLL_INTERVAL_SECONDS;

    @Value("${listing-copy.poll.interval-seconds:15}")
    private long pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;

    @Value("${listing-copy.poll.max-duration-seconds:300}")
    private long pollMaxDurationSeconds = DEFAULT_POLL_MAX_DURATION_SECONDS;

    /**
     * 自注入以触发 @Async 代理（避免同类内部调用绕过代理）。
     */
    @Autowired
    @Lazy
    private ListingCopyService self;

    /**
     * 按卖家 SKU 创建跨站点复制任务并异步执行。
     *
     * @param shopId              店铺 ID
     * @param sku                 卖家 SKU
     * @param sourceMarketplaceId 源 Marketplace ID
     * @param targetMarketplaceId 目标 Marketplace ID
     * @param targetLanguage      目标语言（de/it/es/fr/ja）
     * @param priceMarkup         加价比例（0.20 = 20%）
     * @return 已创建的任务（status=PENDING）
     */
    public ListingCopyTask createCopyTask(Long shopId, String sku,
                                          String sourceMarketplaceId, String targetMarketplaceId,
                                          String targetLanguage, BigDecimal priceMarkup) {
        // 外部写任务必须在请求线程固化身份：@Async 线程没有请求 ThreadLocal，
        // 下游 Feign 需要基于该身份现签 JWT，缺失时必须 fail-closed。
        UserContextSnapshot identity = UserContextSnapshot.capture();

        QueryWrapper<AmzProduct> qw = new QueryWrapper<>();
        qw.eq("shop_id", shopId)
                .eq("sku", sku)
                .eq("marketplace_id", sourceMarketplaceId);
        AmzProduct product = amzProductMapper.selectOne(qw);
        return createTaskFromSource(identity, shopId, sku, sourceMarketplaceId,
                targetMarketplaceId, targetLanguage, priceMarkup, product, "sku", sku);
    }

    /**
     * 按源 ASIN 创建跨站点复制任务并异步执行。
     * ASIN 必须先解析为数据库中的真实卖家 SKU，绝不能把 ASIN 当 SKU 提交。
     */
    public ListingCopyTask createCopyTaskByAsin(Long shopId, String asin,
                                                String sourceMarketplaceId, String targetMarketplaceId,
                                                String targetLanguage, BigDecimal priceMarkup) {
        UserContextSnapshot identity = UserContextSnapshot.capture();
        if (asin == null || asin.isBlank()) {
            throw new IllegalArgumentException("sourceAsin 不能为空");
        }

        QueryWrapper<AmzProduct> qw = new QueryWrapper<>();
        qw.eq("shop_id", shopId)
                .eq("asin", asin.trim())
                .eq("marketplace_id", sourceMarketplaceId);
        List<AmzProduct> products = amzProductMapper.selectList(qw);
        if (products == null || products.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source product not found: shopId=" + shopId + " asin=" + asin
                            + " marketplace=" + sourceMarketplaceId);
        }
        if (products.size() > 1) {
            throw new IllegalArgumentException(
                    "Multiple source products matched ASIN: shopId=" + shopId + " asin=" + asin
                            + " marketplace=" + sourceMarketplaceId);
        }

        AmzProduct product = products.get(0);
        String sku = product.getSku();
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("Source product sku is required for ASIN=" + asin);
        }
        return createTaskFromSource(identity, shopId, sku.trim(), sourceMarketplaceId,
                targetMarketplaceId, targetLanguage, priceMarkup, product, "asin", asin);
    }

    private ListingCopyTask createTaskFromSource(UserContextSnapshot identity, Long shopId, String sku,
                                                 String sourceMarketplaceId, String targetMarketplaceId,
                                                 String targetLanguage, BigDecimal priceMarkup,
                                                 AmzProduct product, String sourceKey, String sourceValue) {
        if (product == null) {
            throw new IllegalArgumentException(
                    "Source product not found: shopId=" + shopId + " " + sourceKey + "=" + sourceValue
                            + " marketplace=" + sourceMarketplaceId);
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("Source product sku is required");
        }
        String normalizedSku = sku.trim();
        String productType = normalizeProductType(product.getProductType());
        if (productType == null) {
            throw new IllegalArgumentException(
                    "Source product productType is required: shopId=" + shopId + " sku=" + normalizedSku
                            + " marketplace=" + sourceMarketplaceId);
        }

        ListingCopyTask task = new ListingCopyTask();
        task.setShopId(shopId);
        task.setSourceMarketplaceId(sourceMarketplaceId);
        task.setTargetMarketplaceId(targetMarketplaceId);
        task.setSku(normalizedSku);
        task.setProductType(productType);
        task.setSourceTitle(product.getTitle());
        task.setSourcePrice(product.getPrice());
        task.setTargetLanguage(targetLanguage);
        task.setPriceMarkup(priceMarkup != null ? priceMarkup : new BigDecimal("0.20"));
        task.setStatus("PENDING");
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        listingCopyTaskMapper.insert(task);

        log.info("Listing copy task created id={} shopId={} sku={} {} -> {}",
                task.getId(), shopId, normalizedSku, sourceMarketplaceId, targetMarketplaceId);

        self.executeCopyTaskAsync(task.getId(), identity);
        return task;
    }

    /**
     * 异步执行复制任务：翻译 -> 汇率换算 -> 加价 -> 提交 Feed -> 轮询状态。
     *
     * @param taskId   任务 ID
     * @param identity 请求线程固化的用户身份快照
     */
    @Async
    public void executeCopyTaskAsync(Long taskId, UserContextSnapshot identity) {
        identity.bind();
        ListingCopyTask task = null;
        try {
            task = listingCopyTaskMapper.selectById(taskId);
            if (task == null) {
                log.warn("executeCopyTaskAsync: task not found id={}", taskId);
                return;
            }

            // 标记处理中
            task.setStatus("PROCESSING");
            task.setUpdateTime(LocalDateTime.now());
            listingCopyTaskMapper.updateById(task);

            // 1. 翻译标题（源语言默认 en）
            String targetLang = task.getTargetLanguage();
            String translatedTitle = translationService.translate(
                    task.getSourceTitle(), "en", targetLang);
            task.setTargetTitle(translatedTitle);

            // 2. 汇率换算
            String[] sourceInfo = MARKETPLACE_MAP.get(task.getSourceMarketplaceId());
            String[] targetInfo = MARKETPLACE_MAP.get(task.getTargetMarketplaceId());
            if (sourceInfo == null || targetInfo == null) {
                throw new IllegalStateException("Unknown marketplace: source="
                        + task.getSourceMarketplaceId() + " target=" + task.getTargetMarketplaceId());
            }
            String sourceCurrency = sourceInfo[1];
            String targetCurrency = targetInfo[1];
            BigDecimal rate = exchangeRateService.getRate(sourceCurrency, targetCurrency);
            task.setExchangeRate(rate);

            // 3. 加价：targetPrice = sourcePrice × rate × (1 + markup)
            BigDecimal sourcePrice = task.getSourcePrice() != null
                    ? task.getSourcePrice() : BigDecimal.ZERO;
            BigDecimal markup = task.getPriceMarkup() != null
                    ? task.getPriceMarkup() : BigDecimal.ZERO;
            BigDecimal targetPrice = sourcePrice
                    .multiply(rate)
                    .multiply(BigDecimal.ONE.add(markup))
                    .setScale(2, RoundingMode.HALF_UP);
            task.setTargetPrice(targetPrice);

            log.info("Task {} computed: titleLen={} rate={} targetPrice={} {}",
                    taskId, translatedTitle.length(), rate, targetPrice, targetCurrency);

            // 4. 提交 Feed
            String jsonlContent = buildJsonl(task, targetCurrency);
            String feedSubmissionId = listingsClient.submitFeed(
                    task.getShopId(), task.getTargetMarketplaceId(), jsonlContent);

            // 5. 登记可恢复轮询计划；状态查询由 @Scheduled 分次执行，绝不阻塞本工作线程。
            LocalDateTime submittedAt = LocalDateTime.now();
            task.setFeedSubmissionId(feedSubmissionId);
            task.setStatus(STATUS_SUBMITTED);
            task.setPollAttempts(0);
            task.setLastPolledAt(null);
            task.setNextPollTime(submittedAt.plusSeconds(positiveSeconds(
                    initialDelaySeconds, DEFAULT_POLL_INTERVAL_SECONDS)));
            task.setPollDeadline(submittedAt.plusSeconds(positiveSeconds(
                    pollMaxDurationSeconds, DEFAULT_POLL_MAX_DURATION_SECONDS)));
            task.setErrorMessage(null);
            task.setUpdateTime(submittedAt);
            listingCopyTaskMapper.updateById(task);

        } catch (Exception e) {
            log.error("executeCopyTaskAsync failed taskId={}", taskId, e);
            if (task != null) {
                task.setStatus("FAILED");
                task.setErrorMessage(e.getMessage());
                task.setUpdateTime(LocalDateTime.now());
                listingCopyTaskMapper.updateById(task);
            }
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 扫描一批到期任务并逐个执行单次状态查询。
     * <p>本方法不睡眠、不阻塞等待 Feed 完成；未完成的任务只更新下一次轮询时间，
     * 由下一次调度继续处理，因此进程重启后仍可恢复。
     *
     * @param batchSize 本批最多处理的任务数
     * @return 实际处理的任务数
     */
    public int pollDueFeedTasks(int batchSize) {
        int safeBatchSize = Math.max(1, Math.min(batchSize, MAX_POLL_BATCH_SIZE));
        LocalDateTime now = LocalDateTime.now();
        QueryWrapper<ListingCopyTask> query = new QueryWrapper<>();
        query.eq("status", STATUS_SUBMITTED)
                .isNotNull("feed_submission_id")
                .and(wrapper -> wrapper.isNull("next_poll_time")
                        .or().le("next_poll_time", now))
                .orderByAsc("next_poll_time")
                .last("LIMIT " + safeBatchSize);
        List<ListingCopyTask> dueTasks = listingCopyTaskMapper.selectList(query);
        if (dueTasks == null || dueTasks.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (ListingCopyTask task : dueTasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            try {
                pollFeedStatus(task.getId());
                processed++;
            } catch (Exception e) {
                // 单条任务落库失败不能中断整批；下轮仍会根据 next_poll_time 重试。
                log.error("pollDueFeedTasks task failed taskId={}", task.getId(), e);
            }
        }
        return processed;
    }

    /**
     * 对单个 Feed 执行一次状态查询并持久化状态。
     * <p>调用方可以是请求线程或调度线程。调度线程缺少请求上下文时，会绑定
     * {@code SYSTEM_USER_ID + 当前任务店铺} 的最小身份，确保下游 Feign 鉴权链可用，
     * 且不会扩大到其他店铺。调用结束仅清理由本方法创建的身份。
     *
     * @param taskId 任务 ID
     */
    public void pollFeedStatus(Long taskId) {
        ListingCopyTask task = listingCopyTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("pollFeedStatus: task not found id={}", taskId);
            return;
        }
        if (!STATUS_SUBMITTED.equals(task.getStatus())) {
            log.debug("pollFeedStatus: task {} is already terminal or inactive, status={}",
                    taskId, task.getStatus());
            return;
        }
        String feedSubmissionId = task.getFeedSubmissionId();
        if (feedSubmissionId == null || feedSubmissionId.isBlank()) {
            markFailed(task, "Feed submission id is missing");
            listingCopyTaskMapper.updateById(task);
            log.warn("pollFeedStatus: no feedSubmissionId for task {}", taskId);
            return;
        }

        boolean systemContextBound = bindSystemContextIfAbsent(task.getShopId());
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime deadline = resolveDeadline(task, now);
            task.setPollAttempts((task.getPollAttempts() == null ? 0 : task.getPollAttempts()) + 1);
            task.setLastPolledAt(now);

            if (!now.isBefore(deadline)) {
                markTimeout(task, "Feed processing timeout before status query");
                listingCopyTaskMapper.updateById(task);
                log.warn("Task {} TIMEOUT before poll, feedId={}", taskId, feedSubmissionId);
                return;
            }

            JsonObject status;
            try {
                status = listingsClient.getFeedStatus(task.getShopId(), feedSubmissionId);
            } catch (Exception e) {
                String message = safeMessage(e);
                task.setErrorMessage("Feed status query failed: " + message);
                if (!scheduleNextPoll(task, LocalDateTime.now(), deadline)) {
                    log.warn("Task {} TIMEOUT after status query failure, feedId={}", taskId, feedSubmissionId);
                }
                listingCopyTaskMapper.updateById(task);
                log.warn("pollFeedStatus failed taskId={} feedId={} err={}",
                        taskId, feedSubmissionId, message);
                return;
            }

            String processingStatus = extractProcessingStatus(status);
            log.info("pollFeedStatus taskId={} feedId={} status={}",
                    taskId, feedSubmissionId, processingStatus);

            if ("DONE".equals(processingStatus)) {
                handleDone(task, taskId, feedSubmissionId, deadline);
                listingCopyTaskMapper.updateById(task);
                return;
            }
            if ("FATAL".equals(processingStatus) || "CANCELLED".equals(processingStatus)) {
                markFailed(task, "Feed processing " + processingStatus);
                listingCopyTaskMapper.updateById(task);
                log.warn("Task {} FAILED: feed {}", taskId, processingStatus);
                return;
            }

            if (!scheduleNextPoll(task, LocalDateTime.now(), deadline)) {
                log.warn("Task {} TIMEOUT waiting for Feed, lastStatus={}",
                        taskId, processingStatus);
            }
            listingCopyTaskMapper.updateById(task);
        } finally {
            if (systemContextBound) {
                UserContext.clear();
            }
        }
    }

    private boolean bindSystemContextIfAbsent(Long shopId) {
        if (UserContext.getUserId() != null) {
            return false;
        }
        if (shopId == null) {
            throw new IllegalStateException("Listing copy task shopId is required");
        }
        UserContext.setUserId(SYSTEM_USER_ID);
        UserContext.setRole(SYSTEM_ROLE);
        UserContext.setShopId(shopId);
        UserContext.setShops(List.of(shopId));
        return true;
    }

    private String extractProcessingStatus(JsonObject status) {
        if (status == null || !status.has("processingStatus")
                || status.get("processingStatus").isJsonNull()) {
            return "PROCESSING";
        }
        try {
            String value = status.get("processingStatus").getAsString();
            return value == null || value.isBlank() ? "PROCESSING" : value;
        } catch (Exception e) {
            return "PROCESSING";
        }
    }

    /**
     * processingStatus=DONE 只代表 Feed 处理结束。必须读取结构化 processing report，
     * 按 accepted / invalid / errors 判定业务结果；结果暂不可得时保持可重试，绝不误报成功。
     */
    private void handleDone(ListingCopyTask task, Long taskId, String feedSubmissionId,
                            LocalDateTime deadline) {
        JsonObject result;
        try {
            result = listingsClient.getFeedResult(task.getShopId(), feedSubmissionId);
        } catch (Exception e) {
            String message = safeMessage(e);
            task.setErrorMessage("Feed result query failed: " + message);
            if (!scheduleNextPoll(task, LocalDateTime.now(), deadline)) {
                log.warn("Task {} TIMEOUT after result query failure, feedId={}", taskId, feedSubmissionId);
            }
            log.warn("pollFeedStatus result failed taskId={} feedId={} err={}",
                    taskId, feedSubmissionId, message);
            return;
        }

        Integer processed = intValue(result, "messagesProcessed");
        Integer accepted = intValue(result, "messagesAccepted");
        Integer invalid = intValue(result, "messagesInvalid");
        Integer errors = intValue(result, "errors");
        Boolean successful = booleanValue(result, "successful");
        Boolean partial = booleanValue(result, "partial");
        Boolean failed = booleanValue(result, "failed");

        boolean allAccepted = Boolean.TRUE.equals(successful)
                && accepted != null && accepted > 0
                && invalid != null && invalid == 0
                && errors != null && errors == 0;
        if (allAccepted) {
            markSuccess(task);
            log.info("Task {} SUCCESS accepted={}", taskId, accepted);
            return;
        }

        boolean hasRejectedRows = (invalid != null && invalid > 0) || (errors != null && errors > 0);
        boolean hasAcceptedRows = accepted != null && accepted > 0;
        if (Boolean.TRUE.equals(partial) || (hasAcceptedRows && hasRejectedRows)) {
            markPartial(task, "Feed partially accepted: messagesAccepted=" + accepted
                    + ", messagesInvalid=" + invalid + ", errors=" + errors);
            log.warn("Task {} PARTIAL accepted={} invalid={} errors={}",
                    taskId, accepted, invalid, errors);
            return;
        }

        boolean noAcceptedRows = accepted != null && accepted == 0;
        boolean processedOrErrored = (processed != null && processed > 0)
                || (errors != null && errors > 0);
        if (Boolean.TRUE.equals(failed) || (noAcceptedRows && processedOrErrored)) {
            markFailed(task, "Feed rejected: messagesAccepted=" + accepted
                    + ", messagesInvalid=" + invalid + ", errors=" + errors);
            log.warn("Task {} FAILED accepted={} invalid={} errors={}",
                    taskId, accepted, invalid, errors);
            return;
        }

        task.setErrorMessage("Feed processing report is incomplete or ambiguous");
        if (!scheduleNextPoll(task, LocalDateTime.now(), deadline)) {
            log.warn("Task {} TIMEOUT waiting for complete processing report, feedId={}",
                    taskId, feedSubmissionId);
        }
        log.warn("pollFeedStatus ambiguous result taskId={} feedId={} processed={} accepted={} invalid={} errors={}",
                taskId, feedSubmissionId, processed, accepted, invalid, errors);
    }

    private Integer intValue(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Boolean booleanValue(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void markPartial(ListingCopyTask task, String message) {
        task.setStatus(STATUS_PARTIAL);
        task.setNextPollTime(null);
        task.setErrorMessage(message);
        task.setUpdateTime(LocalDateTime.now());
    }
    private void markSuccess(ListingCopyTask task) {
        task.setStatus(STATUS_SUCCESS);
        task.setNextPollTime(null);
        task.setErrorMessage(null);
        task.setUpdateTime(LocalDateTime.now());
    }

    private void markFailed(ListingCopyTask task, String message) {
        task.setStatus(STATUS_FAILED);
        task.setNextPollTime(null);
        task.setErrorMessage(message);
        task.setUpdateTime(LocalDateTime.now());
    }

    private void markTimeout(ListingCopyTask task, String message) {
        task.setStatus(STATUS_TIMEOUT);
        task.setNextPollTime(null);
        task.setErrorMessage(message);
        task.setUpdateTime(LocalDateTime.now());
    }

    /**
     * 安排下一次轮询；若下一次时间已经越过截止时间，则直接标记 TIMEOUT。
     *
     * @return true 表示已安排重试，false 表示已超时
     */
    private boolean scheduleNextPoll(ListingCopyTask task, LocalDateTime now, LocalDateTime deadline) {
        LocalDateTime nextPollTime = now.plusSeconds(positiveSeconds(
                pollIntervalSeconds, DEFAULT_POLL_INTERVAL_SECONDS));
        if (!nextPollTime.isBefore(deadline)) {
            markTimeout(task, "Feed processing timeout before completion");
            return false;
        }
        task.setStatus(STATUS_SUBMITTED);
        task.setNextPollTime(nextPollTime);
        task.setUpdateTime(now);
        return true;
    }

    private LocalDateTime resolveDeadline(ListingCopyTask task, LocalDateTime now) {
        if (task.getPollDeadline() != null) {
            return task.getPollDeadline();
        }
        LocalDateTime baseline = task.getUpdateTime() != null
                ? task.getUpdateTime() : task.getCreateTime();
        if (baseline == null) {
            baseline = now;
        }
        LocalDateTime deadline = baseline.plusSeconds(positiveSeconds(
                pollMaxDurationSeconds, DEFAULT_POLL_MAX_DURATION_SECONDS));
        task.setPollDeadline(deadline);
        return deadline;
    }

    private long positiveSeconds(long configured, int fallback) {
        return configured > 0 ? configured : fallback;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    /**
     * 构造提交给 SP-API 的 JSONL 内容（单行 JSON）。
     */
    private String buildJsonl(ListingCopyTask task, String targetCurrency) {
        // 构造符合 JSON_LISTINGS_FEED 规范（v2021-06-30）的单条记录：
        // 每行一个部分更新对象，包含 sku + productType + attributes（title / prices 数组）。
        JsonObject obj = new JsonObject();
        obj.addProperty("sku", task.getSku());
        obj.addProperty("productType", task.getProductType());
        JsonObject attributes = new JsonObject();
        JsonObject title = new JsonObject();
        title.addProperty("value", task.getTargetTitle());
        title.addProperty("language_tag", task.getTargetLanguage());
        attributes.add("title", title);
        JsonArray prices = new JsonArray();
        JsonObject price = new JsonObject();
        price.addProperty("currency", targetCurrency);
        price.addProperty("amount", task.getTargetPrice());
        prices.add(price);
        attributes.add("prices", prices);
        obj.add("attributes", attributes);
        return obj.toString();
    }

    private String normalizeProductType(String productType) {
        if (productType == null || productType.isBlank()) {
            return null;
        }
        return productType.trim();
    }

    /**
     * 请求线程的用户身份快照。shops 做防御性拷贝，避免异步线程读取到可变集合。
     */
    public record UserContextSnapshot(Integer userId, String role, List<Long> shops, Long shopId) {

        static UserContextSnapshot capture() {
            Integer userId = UserContext.getUserId();
            if (userId == null || userId <= 0) {
                throw new SecurityException("未登录或身份无效");
            }
            List<Long> shops = UserContext.getShops();
            return new UserContextSnapshot(
                    userId,
                    UserContext.getRole(),
                    shops == null ? null : List.copyOf(shops),
                    UserContext.getShopId());
        }

        void bind() {
            UserContext.setUserId(userId);
            UserContext.setRole(role);
            UserContext.setShops(shops);
            UserContext.setShopId(shopId);
        }
    }
}
