package com.amz.service;

import com.amz.client.ListingsClient;
import com.amz.context.UserContext;
import com.amz.mapper.AmzProductMapper;
import com.amz.mapper.ListingCopyTaskMapper;
import com.amz.mapper.TranslationCacheMapper;
import com.amz.model.AmzProduct;
import com.amz.model.ListingCopyTask;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ListingCopyService 异步身份与外部写前置条件测试。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ListingCopyServiceTest.TestConfig.class)
@DisplayName("ListingCopyService 身份传播测试")
class ListingCopyServiceTest {

    @Configuration
    @EnableAsync
    static class TestConfig {

        @Bean
        AmzProductMapper amzProductMapper() {
            return Mockito.mock(AmzProductMapper.class);
        }

        @Bean
        ListingCopyTaskMapper listingCopyTaskMapper() {
            return Mockito.mock(ListingCopyTaskMapper.class);
        }

        @Bean
        TranslationService translationService() {
            return Mockito.mock(TranslationService.class);
        }

        @Bean
        TranslationCacheMapper translationCacheMapper() {
            return Mockito.mock(TranslationCacheMapper.class);
        }

        @Bean
        ExchangeRateService exchangeRateService() {
            return Mockito.mock(ExchangeRateService.class);
        }

        @Bean
        ListingsClient listingsClient() {
            return Mockito.mock(ListingsClient.class);
        }

        @Bean(name = "taskExecutor")
        ThreadPoolTaskExecutor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(10);
            executor.setThreadNamePrefix("listing-copy-test-");
            executor.initialize();
            return executor;
        }

        @Bean
        ListingCopyService listingCopyService() {
            return new ListingCopyService();
        }
    }

    @Autowired
    private ListingCopyService listingCopyService;

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

    @Autowired
    @Qualifier("taskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        Mockito.reset(amzProductMapper, listingCopyTaskMapper, translationService,
                exchangeRateService, listingsClient);
        UserContext.clear();
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("异步提交外部 Feed 时应完整传播 JWT 身份，并在任务结束后清理线程变量")
    void asyncWorkerMustSeeCapturedUserContextAndClearItAfterward() throws Exception {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        UserContext.setShopId(1L);

        AmzProduct product = new AmzProduct();
        product.setSku("SKU-1");
        product.setTitle("Source title");
        product.setPrice(new BigDecimal("10.00"));
        product.setCategory("LUGGAGE");
        product.setProductType("LUGGAGE");
        when(amzProductMapper.selectOne(any(QueryWrapper.class))).thenReturn(product);
        when(amzProductMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(product));

        AtomicReference<ListingCopyTask> insertedTask = new AtomicReference<>();
        doAnswer(invocation -> {
            ListingCopyTask task = invocation.getArgument(0);
            task.setId(42L);
            insertedTask.set(task);
            return 1;
        }).when(listingCopyTaskMapper).insert(any(ListingCopyTask.class));
        when(listingCopyTaskMapper.selectById(42L)).thenAnswer(invocation -> insertedTask.get());

        when(translationService.translate("Source title", "en", "de")).thenReturn("Quellentitel");
        when(exchangeRateService.getRate("USD", "EUR")).thenReturn(new BigDecimal("0.90"));

        CountDownLatch submitObserved = new CountDownLatch(1);
        AtomicReference<Integer> workerUserId = new AtomicReference<>();
        AtomicReference<String> workerRole = new AtomicReference<>();
        AtomicReference<List<Long>> workerShops = new AtomicReference<>();
        AtomicReference<Long> workerShopId = new AtomicReference<>();
        when(listingsClient.submitFeed(eq(1L), eq("A1PA6795UKMFR9"), anyString()))
                .thenAnswer(invocation -> {
                    workerUserId.set(UserContext.getUserId());
                    workerRole.set(UserContext.getRole());
                    workerShops.set(UserContext.getShops());
                    workerShopId.set(UserContext.getShopId());
                    submitObserved.countDown();
                    throw new IllegalStateException("stop-after-submit");
                });

        listingCopyService.createCopyTask(1L, "SKU-1", "ATVPDKIKX0DER",
                "A1PA6795UKMFR9", "de", new BigDecimal("0.20"));

        assertTrue(submitObserved.await(5, TimeUnit.SECONDS), "异步任务应在 5 秒内到达 Feed 提交");
        assertEquals(7, workerUserId.get(), "异步 worker 必须看到请求线程捕获的 userId");
        assertEquals("OPERATOR", workerRole.get(), "异步 worker 必须看到 role");
        assertEquals(List.of(1L), workerShops.get(), "异步 worker 必须看到授权店铺");
        assertEquals(1L, workerShopId.get(), "异步 worker 必须看到当前店铺");
        assertEquals(7, UserContext.getUserId(), "调用线程身份不能被异步任务清理动作影响");

        verify(listingCopyTaskMapper, timeout(5000).atLeastOnce())
                .updateById(argThat((ListingCopyTask task) -> "FAILED".equals(task.getStatus())));

        Integer leakedUserId = taskExecutor.submit(UserContext::getUserId).get(5, TimeUnit.SECONDS);
        assertNull(leakedUserId, "异步任务结束后必须清理 ThreadLocal，避免池复用串号");
    }

    @Test
    @DisplayName("无认证身份时应在数据库查询和外部写之前 fail-closed")
    void createCopyTaskWithoutAuthenticatedUserFailsClosed() {
        UserContext.clear();

        assertThrows(SecurityException.class, () -> listingCopyService.createCopyTask(
                1L, "SKU-1", "ATVPDKIKX0DER", "A1PA6795UKMFR9",
                "de", new BigDecimal("0.20")));

        verifyNoInteractions(amzProductMapper, listingCopyTaskMapper, listingsClient);
    }

    @Test
    @DisplayName("按 ASIN 创建任务时必须解析为真实卖家 SKU，并按 ASIN 查询源 Listing")
    void createCopyTaskByAsinMustResolveSellerSku() throws Exception {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        UserContext.setShopId(1L);

        AmzProduct product = new AmzProduct();
        product.setSku("SKU-REAL");
        product.setAsin("B08X4");
        product.setTitle("Source title");
        product.setPrice(new BigDecimal("10.00"));
        product.setProductType("LUGGAGE");
        when(amzProductMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(product));

        AtomicReference<ListingCopyTask> insertedTask = new AtomicReference<>();
        doAnswer(invocation -> {
            ListingCopyTask task = invocation.getArgument(0);
            task.setId(43L);
            insertedTask.set(task);
            return 1;
        }).when(listingCopyTaskMapper).insert(any(ListingCopyTask.class));
        when(listingCopyTaskMapper.selectById(43L)).thenAnswer(invocation -> insertedTask.get());
        when(translationService.translate("Source title", "en", "de")).thenReturn("Quellentitel");
        when(exchangeRateService.getRate("USD", "EUR")).thenReturn(new BigDecimal("0.90"));

        CountDownLatch submitObserved = new CountDownLatch(1);
        when(listingsClient.submitFeed(eq(1L), eq("A1PA6795UKMFR9"), anyString()))
                .thenAnswer(invocation -> {
                    submitObserved.countDown();
                    throw new IllegalStateException("stop-after-submit");
                });

        listingCopyService.createCopyTaskByAsin(1L, "B08X4", "ATVPDKIKX0DER",
                "A1PA6795UKMFR9", "de", new BigDecimal("0.20"));

        verify(listingCopyTaskMapper).insert(argThat((ListingCopyTask task) -> "SKU-REAL".equals(task.getSku())));
        assertNotNull(insertedTask.get());
        assertTrue(submitObserved.await(5, TimeUnit.SECONDS), "异步任务应在 5 秒内到达 Feed 提交");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AmzProduct>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(amzProductMapper).selectList(queryCaptor.capture());
        QueryWrapper<AmzProduct> query = queryCaptor.getValue();
        assertTrue(query.getSqlSegment().contains("asin"), "必须按 asin 列查询，不能把 ASIN 当 SKU");
        assertTrue(query.getParamNameValuePairs().containsValue("B08X4"), "查询参数必须使用源 ASIN");
    }

    @Test
    @DisplayName("源商品缺少真实 productType 时不得插入任务或提交 Feed")
    void createCopyTaskRejectsMissingProductType() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        UserContext.setShopId(1L);

        AmzProduct product = new AmzProduct();
        product.setSku("SKU-1");
        product.setTitle("Source title");
        product.setPrice(new BigDecimal("10.00"));
        product.setCategory("LUGGAGE");
        when(amzProductMapper.selectOne(any(QueryWrapper.class))).thenReturn(product);
        when(amzProductMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(product));

        assertThrows(IllegalArgumentException.class, () -> listingCopyService.createCopyTask(
                1L, "SKU-1", "ATVPDKIKX0DER", "A1PA6795UKMFR9",
                "de", new BigDecimal("0.20")));

        verifyNoInteractions(listingCopyTaskMapper, listingsClient);
    }

    @Test
    @DisplayName("JSONL productType 必须使用源商品的真实商品类型")
    void buildFeedMustUseSourceProductType() throws Exception {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        UserContext.setShopId(1L);

        AmzProduct product = new AmzProduct();
        product.setSku("SKU-1");
        product.setTitle("Source title");
        product.setPrice(new BigDecimal("10.00"));
        product.setCategory("LUGGAGE");
        product.setProductType("LUGGAGE");
        when(amzProductMapper.selectOne(any(QueryWrapper.class))).thenReturn(product);
        when(amzProductMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(product));

        AtomicReference<ListingCopyTask> insertedTask = new AtomicReference<>();
        doAnswer(invocation -> {
            ListingCopyTask task = invocation.getArgument(0);
            task.setId(42L);
            insertedTask.set(task);
            return 1;
        }).when(listingCopyTaskMapper).insert(any(ListingCopyTask.class));
        when(listingCopyTaskMapper.selectById(42L)).thenAnswer(invocation -> insertedTask.get());

        when(translationService.translate("Source title", "en", "de")).thenReturn("Quellentitel");
        when(exchangeRateService.getRate("USD", "EUR")).thenReturn(new BigDecimal("0.90"));

        CountDownLatch submitObserved = new CountDownLatch(1);
        AtomicReference<String> submittedJsonl = new AtomicReference<>();
        when(listingsClient.submitFeed(eq(1L), eq("A1PA6795UKMFR9"), anyString()))
                .thenAnswer(invocation -> {
                    submittedJsonl.set(invocation.getArgument(2));
                    submitObserved.countDown();
                    throw new IllegalStateException("stop-after-submit");
                });

        listingCopyService.createCopyTask(1L, "SKU-1", "ATVPDKIKX0DER",
                "A1PA6795UKMFR9", "de", new BigDecimal("0.20"));

        assertTrue(submitObserved.await(5, TimeUnit.SECONDS), "异步任务应在 5 秒内到达 Feed 提交");
        JsonObject jsonLine = JsonParser.parseString(submittedJsonl.get()).getAsJsonObject();
        assertEquals("LUGGAGE", jsonLine.get("productType").getAsString(),
                "不得把占位值 PRODUCT 提交给 Amazon");
    }

    @Test
    @DisplayName("Feed 提交后只登记轮询计划，异步工作线程不得阻塞等待处理结果")
    void submittedFeedMustSchedulePollingWithoutBlockingWorker() throws Exception {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        UserContext.setShopId(1L);

        AmzProduct product = new AmzProduct();
        product.setSku("SKU-1");
        product.setTitle("Source title");
        product.setPrice(new BigDecimal("10.00"));
        product.setProductType("LUGGAGE");
        when(amzProductMapper.selectOne(any(QueryWrapper.class))).thenReturn(product);

        AtomicReference<ListingCopyTask> persistedTask = new AtomicReference<>();
        doAnswer(invocation -> {
            ListingCopyTask task = invocation.getArgument(0);
            task.setId(44L);
            persistedTask.set(task);
            return 1;
        }).when(listingCopyTaskMapper).insert(any(ListingCopyTask.class));
        when(listingCopyTaskMapper.selectById(44L)).thenAnswer(invocation -> persistedTask.get());

        when(translationService.translate("Source title", "en", "de")).thenReturn("Quellentitel");
        when(exchangeRateService.getRate("USD", "EUR")).thenReturn(new BigDecimal("0.90"));
        when(listingsClient.submitFeed(eq(1L), eq("A1PA6795UKMFR9"), anyString()))
                .thenReturn("feed-44");

        CountDownLatch submitted = new CountDownLatch(1);
        when(listingCopyTaskMapper.updateById(any(ListingCopyTask.class))).thenAnswer(invocation -> {
            ListingCopyTask task = invocation.getArgument(0);
            if ("SUBMITTED".equals(task.getStatus())) {
                submitted.countDown();
            }
            return 1;
        });

        listingCopyService.createCopyTask(1L, "SKU-1", "ATVPDKIKX0DER",
                "A1PA6795UKMFR9", "de", new BigDecimal("0.20"));

        assertTrue(submitted.await(5, TimeUnit.SECONDS), "任务应进入 SUBMITTED");
        ListingCopyTask task = persistedTask.get();
        assertEquals("feed-44", task.getFeedSubmissionId());
        assertEquals("SUBMITTED", task.getStatus());
        assertEquals(0, task.getPollAttempts());
        assertNotNull(task.getNextPollTime(), "必须持久化下一次轮询时间");
        assertNotNull(task.getPollDeadline(), "必须持久化轮询截止时间，避免任务永久悬挂");
        assertTrue(task.getNextPollTime().isBefore(task.getPollDeadline()));
        verify(listingsClient, never()).getFeedStatus(any(), anyString());
    }

    @Test
    @DisplayName("DONE 且 processing report 全部 accepted 时才落库 SUCCESS")
    void pollFeedStatusMarksDoneAsSuccess() {
        ListingCopyTask task = submittedTask(50L);
        task.setPollAttempts(2);
        when(listingCopyTaskMapper.selectById(50L)).thenReturn(task);
        when(listingsClient.getFeedStatus(1L, "feed-50")).thenReturn(doneStatus());
        when(listingsClient.getFeedResult(1L, "feed-50"))
                .thenReturn(feedResult(1, 0, 0, true, false, false));

        listingCopyService.pollFeedStatus(50L);

        assertEquals("SUCCESS", task.getStatus());
        assertEquals(3, task.getPollAttempts());
        assertNotNull(task.getLastPolledAt());
        assertNull(task.getNextPollTime());
        assertNull(task.getErrorMessage());
    }

    @Test
    @DisplayName("DONE 且部分消息被拒绝时必须落库 PARTIAL，不能报 SUCCESS")
    void pollFeedStatusMarksPartialWhenSomeMessagesRejected() {
        ListingCopyTask task = submittedTask(55L);
        when(listingCopyTaskMapper.selectById(55L)).thenReturn(task);
        when(listingsClient.getFeedStatus(1L, "feed-55")).thenReturn(doneStatus());
        when(listingsClient.getFeedResult(1L, "feed-55"))
                .thenReturn(feedResult(2, 1, 1, false, true, false));

        listingCopyService.pollFeedStatus(55L);

        assertEquals("PARTIAL", task.getStatus());
        assertNull(task.getNextPollTime());
        assertTrue(task.getErrorMessage().contains("messagesAccepted=2"));
        assertTrue(task.getErrorMessage().contains("messagesInvalid=1"));
    }

    @Test
    @DisplayName("DONE 且没有消息被接受时必须落库 FAILED")
    void pollFeedStatusMarksFailedWhenNoMessageAccepted() {
        ListingCopyTask task = submittedTask(56L);
        when(listingCopyTaskMapper.selectById(56L)).thenReturn(task);
        when(listingsClient.getFeedStatus(1L, "feed-56")).thenReturn(doneStatus());
        when(listingsClient.getFeedResult(1L, "feed-56"))
                .thenReturn(feedResult(0, 1, 1, false, false, true));

        listingCopyService.pollFeedStatus(56L);

        assertEquals("FAILED", task.getStatus());
        assertNull(task.getNextPollTime());
        assertTrue(task.getErrorMessage().contains("messagesAccepted=0"));
    }

    @Test
    @DisplayName("DONE 但 processing report 暂不可得时必须保持 SUBMITTED 并重试，绝不能报成功")
    void pollFeedStatusResultFailureKeepsRetryable() {
        ListingCopyTask task = submittedTask(57L);
        when(listingCopyTaskMapper.selectById(57L)).thenReturn(task);
        when(listingsClient.getFeedStatus(1L, "feed-57")).thenReturn(doneStatus());
        when(listingsClient.getFeedResult(1L, "feed-57"))
                .thenThrow(new IllegalStateException("result document unavailable"));

        listingCopyService.pollFeedStatus(57L);

        assertEquals("SUBMITTED", task.getStatus());
        assertNotNull(task.getNextPollTime());
        assertTrue(task.getErrorMessage().contains("result document unavailable"));
    }

    @Test
    @DisplayName("单次轮询遇到处理中状态时必须保留 SUBMITTED 并安排下一轮")
    void pollFeedStatusReschedulesProcessingTask() {
        ListingCopyTask task = submittedTask(51L);
        task.setPollAttempts(1);
        when(listingCopyTaskMapper.selectById(51L)).thenReturn(task);
        JsonObject status = new JsonObject();
        status.addProperty("processingStatus", "IN_PROGRESS");
        when(listingsClient.getFeedStatus(1L, "feed-51")).thenReturn(status);

        LocalDateTime before = LocalDateTime.now();
        listingCopyService.pollFeedStatus(51L);

        assertEquals("SUBMITTED", task.getStatus());
        assertEquals(2, task.getPollAttempts());
        assertNotNull(task.getLastPolledAt());
        assertNotNull(task.getNextPollTime());
        assertTrue(task.getNextPollTime().isAfter(before), "处理中任务必须安排未来轮询");
    }

    @Test
    @DisplayName("已超过轮询截止时间时必须直接标记 TIMEOUT，不再调用外部 API")
    void pollFeedStatusMarksExpiredTaskTimeout() {
        ListingCopyTask task = submittedTask(52L);
        task.setPollDeadline(LocalDateTime.now().minusSeconds(1));
        when(listingCopyTaskMapper.selectById(52L)).thenReturn(task);

        listingCopyService.pollFeedStatus(52L);

        assertEquals("TIMEOUT", task.getStatus());
        assertNull(task.getNextPollTime());
        assertTrue(task.getErrorMessage().contains("timeout"));
        verifyNoInteractions(listingsClient);
    }

    @Test
    @DisplayName("单次状态查询失败时保留可重试状态，而不是吞掉异常后永久悬挂")
    void pollFeedStatusFailureSchedulesRetryBeforeDeadline() {
        ListingCopyTask task = submittedTask(53L);
        task.setPollAttempts(4);
        when(listingCopyTaskMapper.selectById(53L)).thenReturn(task);
        when(listingsClient.getFeedStatus(1L, "feed-53"))
                .thenThrow(new IllegalStateException("SP-API unavailable"));

        listingCopyService.pollFeedStatus(53L);

        assertEquals("SUBMITTED", task.getStatus());
        assertEquals(5, task.getPollAttempts());
        assertNotNull(task.getNextPollTime());
        assertTrue(task.getErrorMessage().contains("SP-API unavailable"));
    }

    @Test
    @DisplayName("调度轮询必须使用仅限当前店铺的系统身份，并在结束后清理线程变量")
    void pollFeedStatusBindsShopScopedSystemIdentityAndClearsIt() {
        ListingCopyTask task = submittedTask(54L);
        when(listingCopyTaskMapper.selectById(54L)).thenReturn(task);

        AtomicReference<Integer> seenUserId = new AtomicReference<>();
        AtomicReference<String> seenRole = new AtomicReference<>();
        AtomicReference<Long> seenShopId = new AtomicReference<>();
        AtomicReference<List<Long>> seenShops = new AtomicReference<>();
        when(listingsClient.getFeedStatus(1L, "feed-54")).thenAnswer(invocation -> {
            seenUserId.set(UserContext.getUserId());
            seenRole.set(UserContext.getRole());
            seenShopId.set(UserContext.getShopId());
            seenShops.set(UserContext.getShops());
            return doneStatus();
        });
        when(listingsClient.getFeedResult(1L, "feed-54"))
                .thenReturn(feedResult(1, 0, 0, true, false, false));

        listingCopyService.pollFeedStatus(54L);

        assertEquals(0, seenUserId.get(), "系统任务应使用非真实用户的系统账号");
        assertEquals("VIEWER", seenRole.get(), "轮询只需最小只读角色");
        assertEquals(1L, seenShopId.get());
        assertEquals(List.of(1L), seenShops.get(), "系统身份只能授权当前任务所属店铺");
        assertNull(UserContext.getUserId(), "调度线程身份必须在任务结束后清理");
        assertNull(UserContext.getShopId());
        assertNull(UserContext.getShops());
    }

    private JsonObject doneStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("processingStatus", "DONE");
        return status;
    }

    private JsonObject feedResult(int accepted, int invalid, int errors,
                                  boolean successful, boolean partial, boolean failed) {
        JsonObject result = new JsonObject();
        result.addProperty("messagesProcessed", accepted + invalid);
        result.addProperty("messagesAccepted", accepted);
        result.addProperty("messagesInvalid", invalid);
        result.addProperty("errors", errors);
        result.addProperty("warnings", 0);
        result.addProperty("successful", successful);
        result.addProperty("partial", partial);
        result.addProperty("failed", failed);
        return result;
    }
    private ListingCopyTask submittedTask(Long id) {
        ListingCopyTask task = new ListingCopyTask();
        task.setId(id);
        task.setShopId(1L);
        task.setSku("SKU-" + id);
        task.setProductType("LUGGAGE");
        task.setTargetMarketplaceId("A1PA6795UKMFR9");
        task.setStatus("SUBMITTED");
        task.setFeedSubmissionId("feed-" + id);
        task.setPollAttempts(0);
        task.setNextPollTime(LocalDateTime.now().minusSeconds(1));
        task.setPollDeadline(LocalDateTime.now().plusMinutes(5));
        return task;
    }
}

