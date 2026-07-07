package com.amz.service;

import com.amz.client.ListingsClient;
import com.amz.mapper.AmzProductMapper;
import com.amz.mapper.ListingCopyTaskMapper;
import com.amz.model.AmzProduct;
import com.amz.model.ListingCopyTask;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 跨站点 Listing 复制服务。
 * <p>
 * 流程：查源 Listing -> 创建任务(PENDING) -> 异步执行(翻译 -> 汇率换算 -> 加价 -> 提交 Feed -> 轮询状态)。
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

    /** 轮询间隔：15 秒。 */
    private static final long POLL_INTERVAL_MS = 15_000L;
    /** 轮询最长等待：5 分钟。 */
    private static final long POLL_MAX_DURATION_MS = 5 * 60_000L;

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

    /**
     * 自注入以触发 @Async 代理（避免同类内部调用绕过代理）。
     */
    @Autowired
    @Lazy
    private ListingCopyService self;

    /**
     * 创建跨站点复制任务并异步执行。
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
        // 1. 查源 Listing
        QueryWrapper<AmzProduct> qw = new QueryWrapper<>();
        qw.eq("shop_id", shopId)
                .eq("sku", sku)
                .eq("marketplace_id", sourceMarketplaceId);
        AmzProduct product = amzProductMapper.selectOne(qw);
        if (product == null) {
            throw new IllegalArgumentException(
                    "Source product not found: shopId=" + shopId + " sku=" + sku
                            + " marketplace=" + sourceMarketplaceId);
        }

        // 2. 创建任务
        ListingCopyTask task = new ListingCopyTask();
        task.setShopId(shopId);
        task.setSourceMarketplaceId(sourceMarketplaceId);
        task.setTargetMarketplaceId(targetMarketplaceId);
        task.setSku(sku);
        task.setSourceTitle(product.getTitle());
        task.setSourcePrice(product.getPrice());
        task.setTargetLanguage(targetLanguage);
        task.setPriceMarkup(priceMarkup != null ? priceMarkup : new BigDecimal("0.20"));
        task.setStatus("PENDING");
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        listingCopyTaskMapper.insert(task);

        log.info("Listing copy task created id={} shopId={} sku={} {} -> {}",
                task.getId(), shopId, sku, sourceMarketplaceId, targetMarketplaceId);

        // 3. 异步执行
        self.executeCopyTaskAsync(task.getId());

        return task;
    }

    /**
     * 异步执行复制任务：翻译 -> 汇率换算 -> 加价 -> 提交 Feed -> 轮询状态。
     *
     * @param taskId 任务 ID
     */
    @Async
    public void executeCopyTaskAsync(Long taskId) {
        ListingCopyTask task = listingCopyTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("executeCopyTaskAsync: task not found id={}", taskId);
            return;
        }
        try {
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

            // 5. 更新状态为 SUBMITTED
            task.setFeedSubmissionId(feedSubmissionId);
            task.setStatus("SUBMITTED");
            task.setUpdateTime(LocalDateTime.now());
            listingCopyTaskMapper.updateById(task);

            // 6. 轮询 Feed 状态
            pollFeedStatus(taskId);

        } catch (Exception e) {
            log.error("executeCopyTaskAsync failed taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            task.setUpdateTime(LocalDateTime.now());
            listingCopyTaskMapper.updateById(task);
        }
    }

    /**
     * 轮询 Feed 处理状态：每 15s 一次，最长 5min。
     * DONE -> SUCCESS / FATAL -> FAILED。
     *
     * @param taskId 任务 ID
     */
    public void pollFeedStatus(Long taskId) {
        ListingCopyTask task = listingCopyTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("pollFeedStatus: task not found id={}", taskId);
            return;
        }
        String feedSubmissionId = task.getFeedSubmissionId();
        if (feedSubmissionId == null) {
            log.warn("pollFeedStatus: no feedSubmissionId for task {}", taskId);
            return;
        }

        long start = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("pollFeedStatus interrupted taskId={}", taskId);
                return;
            }

            JsonObject status = listingsClient.getFeedStatus(task.getShopId(), feedSubmissionId);
            String processingStatus = status.has("processingStatus")
                    ? status.get("processingStatus").getAsString() : "PROCESSING";
            log.info("pollFeedStatus taskId={} feedId={} status={}",
                    taskId, feedSubmissionId, processingStatus);

            if ("DONE".equals(processingStatus)) {
                task.setStatus("SUCCESS");
                task.setUpdateTime(LocalDateTime.now());
                listingCopyTaskMapper.updateById(task);
                log.info("Task {} SUCCESS", taskId);
                return;
            }
            if ("FATAL".equals(processingStatus) || "CANCELLED".equals(processingStatus)) {
                task.setStatus("FAILED");
                task.setErrorMessage("Feed processing " + processingStatus);
                task.setUpdateTime(LocalDateTime.now());
                listingCopyTaskMapper.updateById(task);
                log.warn("Task {} FAILED: feed {}", taskId, processingStatus);
                return;
            }

            // 超时检查
            if (System.currentTimeMillis() - start > POLL_MAX_DURATION_MS) {
                log.warn("pollFeedStatus timeout (5min) taskId={} feedId={} lastStatus={}",
                        taskId, feedSubmissionId, processingStatus);
                return;
            }
        }
    }

    /**
     * 构造提交给 SP-API 的 JSONL 内容（单行 JSON）。
     */
    private String buildJsonl(ListingCopyTask task, String targetCurrency) {
        JsonObject obj = new JsonObject();
        obj.addProperty("sku", task.getSku());
        obj.addProperty("marketplaceId", task.getTargetMarketplaceId());
        obj.addProperty("title", task.getTargetTitle());
        obj.addProperty("price", task.getTargetPrice());
        obj.addProperty("currency", targetCurrency);
        return obj.toString();
    }
}
