package com.amz.service;

import com.amz.context.UserContext;
import com.amz.mapper.AmzProductMapper;
import com.amz.mapper.ListingCopyTaskMapper;
import com.amz.mapper.TranslationCacheMapper;
import com.amz.model.AmzProduct;
import com.amz.model.ListingCopyTask;
import com.amz.client.ListingsClient;import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Listing 复制任务的身份**跨异步线程**传播与清理。
 * <p>
 * <b>为什么单独一个类</b>：同模块的 {@link ListingCopyServiceTest} 为了让 4 个用例
 * 摆脱时序依赖，把 {@code taskExecutor} 换成了 {@code SyncTaskExecutor}
 * ——{@code @Async} 变成在调用线程内直接跑完。
 * 那对「业务结果」类断言是好事，但会让「身份传播」这条逻辑变得不可测：
 * 传播本来就是为跨线程设计的，同一线程里 {@code UserContext} 的身份还在，
 * {@code identity.bind()} 沦为无操作。
 * <p>
 * 实测证据：在同步执行器下删掉生产代码的 {@code identity.bind()}，
 * {@code ListingCopyServiceTest} 仍 15/15 全绿——也就是说那条
 * 「异步 worker 必须看到 userId=7」的断言当时并没有在测任何东西。
 * 所以这个用例必须有一个**真正的另一线程**，单独放一个类、独占线程池。
 * <p>
 * 代价是重新引入 latch 等待；但本类只有这一个用例，没有其它用例来抢
 * core=1 的池，5 秒窗口是充裕的（原先的 flaky 正是 4 个用例共享单线程池 +
 * 排队，在 CI 负载高时窗口不够）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ListingCopyIdentityPropagationTest.TestConfig.class)
@DisplayName("Listing 复制：身份必须跨异步线程传播，任务结束后清理线程变量")
class ListingCopyIdentityPropagationTest {

    @Configuration
    @EnableAsync
    static class TestConfig {

        @Bean
        AmzProductMapper amzProductMapper() {
            return mock(AmzProductMapper.class);
        }

        @Bean
        ListingCopyTaskMapper listingCopyTaskMapper() {
            return mock(ListingCopyTaskMapper.class);
        }

        @Bean
        TranslationService translationService() {
            return mock(TranslationService.class);
        }

        @Bean
        TranslationCacheMapper translationCacheMapper() {
            return mock(TranslationCacheMapper.class);
        }

        @Bean
        ExchangeRateService exchangeRateService() {
            return mock(ExchangeRateService.class);
        }

        @Bean
        ListingsClient listingsClient() {
            return mock(ListingsClient.class);
        }

        /** 单线程池，仅供本类这一个用例使用。 */
        @Bean(name = "taskExecutor")
        Executor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(10);
            executor.setThreadNamePrefix("listing-copy-identity-");
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
    private Executor taskExecutor;

    @BeforeEach
    void resetContext() {
        UserContext.clear();
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("worker 线程读到请求线程捕获的身份；任务结束后 ThreadLocal 被清理，不复用串号")
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

        when(translationService.translateWithOutcome("Source title", "en", "de"))
                .thenReturn(new TranslationService.TranslationOutcome("Quellentitel", false));
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

        // 真线程池：必须等「另一线程」真正跑到 Feed 提交这一步。
        assertTrue(submitObserved.await(5, TimeUnit.SECONDS), "异步任务应在 5 秒内到达 Feed 提交");
        assertEquals(7, workerUserId.get(), "异步 worker 必须看到请求线程捕获的 userId");
        assertEquals("OPERATOR", workerRole.get(), "异步 worker 必须看到 role");
        assertEquals(List.of(1L), workerShops.get(), "异步 worker 必须看到授权店铺");
        assertEquals(1L, workerShopId.get(), "异步 worker 必须看到当前店铺");
        // 请求线程的身份不被 worker 的清理动作影响——这条只有在真线程池下才成立，
        // 也正是 bind/clear 必须成对出现的原因。
        assertEquals(7, UserContext.getUserId(), "调用线程身份不能被异步任务的清理动作影响");

        // 复用一个线程：上一个任务的身份必须已被清掉，否则会读到别人的 userId。
        AtomicReference<Integer> leakedUserId = new AtomicReference<>();
        CountDownLatch reuseDone = new CountDownLatch(1);
        taskExecutor.execute(() -> {
            leakedUserId.set(UserContext.getUserId());
            reuseDone.countDown();
        });
        assertTrue(reuseDone.await(5, TimeUnit.SECONDS), "复用一个线程的任务应执行完");
        assertNull(leakedUserId.get(), "异步任务结束后必须清理 ThreadLocal，避免池复用串号");
    }

    @Test
    @DisplayName("Feed 的 JSONL productType 必须来自源商品，不能用占位值")
    void buildFeedMustUseSourceProductType() throws Exception {
        AmzProduct product = new AmzProduct();
        product.setSku("SKU-1");
        product.setTitle("Source title");
        product.setPrice(new BigDecimal("10.00"));
        product.setCategory("LUGGAGE");
        product.setProductType("LUGGAGE");
        when(amzProductMapper.selectOne(any(QueryWrapper.class))).thenReturn(product);
        when(amzProductMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(product));
        when(translationService.translateWithOutcome("Source title", "en", "de"))
                .thenReturn(new TranslationService.TranslationOutcome("Quellentitel", false));
        when(exchangeRateService.getRate("USD", "EUR")).thenReturn(new BigDecimal("0.90"));

        AtomicReference<String> submittedJsonl = new AtomicReference<>();
        CountDownLatch submitObserved = new CountDownLatch(1);
        when(listingsClient.submitFeed(eq(1L), eq("A1PA6795UKMFR9"), anyString()))
                .thenAnswer(invocation -> {
                    submittedJsonl.set(invocation.getArgument(2));
                    submitObserved.countDown();
                    throw new IllegalStateException("stop-after-submit");
                });

        UserContext.setUserId(7);
        UserContext.setShops(List.of(1L));
        listingCopyService.createCopyTask(1L, "SKU-1", "ATVPDKIKX0DER",
                "A1PA6795UKMFR9", "de", new BigDecimal("0.20"));

        assertTrue(submitObserved.await(5, TimeUnit.SECONDS));
        JsonObject jsonLine = com.google.gson.JsonParser.parseString(submittedJsonl.get()).getAsJsonObject();
        assertEquals("LUGGAGE", jsonLine.get("productType").getAsString(),
                "不得把占位值 PRODUCT 提交给 Amazon");

        ArgumentCaptor<ListingCopyTask> captor = ArgumentCaptor.forClass(ListingCopyTask.class);
        verify(listingCopyTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        ListingCopyTask failed = captor.getAllValues().stream()
                .filter(t -> "FAILED".equals(t.getStatus()))
                .findFirst().orElse(null);
        assertNotNull(failed, "外部写失败必须把任务标记为 FAILED");
    }
}
