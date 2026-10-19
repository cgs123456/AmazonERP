package com.amz.scheduler;

import com.amz.connector.SpApiEndpointResolver;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.lock.DistributedJobLock;
import com.amz.service.impl.SalesHistorySyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;




import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销量历史调度。
 * <p>
 * <b>为什么这条测试必须存在</b>：{@code SalesHistorySyncService} 上一轮就写完了，
 * 测试也绿，但**全仓没有任何调用方**——服务是死代码，{@code amz_sales_history}
 * 依然为空，补货建议继续基于空历史出数。这正是「实现完整 ≠ 已接通」的典型形状：
 * 没有调度器测试时，这类接线缺失在任何编译/单测里都不可见。
 * <p>
 * 判据刻意盯**调用次数、店铺集合与 marketplaceId 来源**：
 * <ul>
 *   <li>默认关闭却仍发请求 → 未配凭证的环境反复报错；</li>
 *   <li>只同步第一个店铺 → 其余店铺永远没有销量历史；</li>
 *   <li>用全局配置的 marketplaceId 而不是凭证里的 → 多站点店铺拉错站；</li>
 *   <li>缺 marketplaceId 时静默跳过 → 「没配置」看起来像「没销量」。</li>
 * </ul>
 */
@DisplayName("销量历史调度：默认关、逐店、站点取自凭证")
class SalesHistorySyncSchedulerTest {

    private final SalesHistorySyncService service = mock(SalesHistorySyncService.class);
    private final ShopCredentialStore credentialStore = mock(ShopCredentialStore.class);
    private final DistributedJobLock lock = mock(DistributedJobLock.class);
    private final SpApiEndpointResolver endpointResolver = mock(SpApiEndpointResolver.class);

    private SalesHistorySyncScheduler scheduler(boolean enabled) {
        SalesHistorySyncScheduler s = new SalesHistorySyncScheduler();
        ReflectionTestUtils.setField(s, "salesHistorySyncService", service);
        ReflectionTestUtils.setField(s, "credentialStore", credentialStore);
        ReflectionTestUtils.setField(s, "distributedJobLock", lock);
        ReflectionTestUtils.setField(s, "spApiEndpointResolver", endpointResolver);
        ReflectionTestUtils.setField(s, "syncEnabled", enabled);
        ReflectionTestUtils.setField(s, "syncDays", 30);
        // 让幂等锁真的执行任务体，否则「跑过了」和「没跑」断言起来一样
        doAnswer(inv -> {
            java.util.function.Supplier<?> action = inv.getArgument(2, java.util.function.Supplier.class);
            return action.get();
        }).when(lock).runIdempotentWithLock(anyString(), anyLong(),
                any(java.util.function.Supplier.class), any());
        return s;
    }

    @Test
    @DisplayName("默认关闭：不加锁、不查凭证、不调报表")
    void disabledByDefaultDoesNothing() {
        scheduler(false).syncDaily();

        verify(lock, never()).runIdempotentWithLock(anyString(), anyLong(), any(), any());
        verify(service, never()).sync(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("端点覆盖生效时跳过：桩数据不得写进业务表")
    void skipsWhenEndpointOverrideActive() {
        when(endpointResolver.isOverrideActive()).thenReturn(true);
        when(endpointResolver.baseUrlOverride()).thenReturn("http://127.0.0.1:19099");

        scheduler(true).syncDaily();

        verify(service, never()).sync(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("逐店同步，且 marketplaceId 取自该店凭证（不是全局配置）")
    void syncsEveryShopWithItsOwnMarketplace() {
        Set<Long> shops = new LinkedHashSet<>(List.of(1L, 2L, 3L));
        when(credentialStore.getActiveShopIds()).thenReturn(shops);
        when(credentialStore.get(1L)).thenReturn(credential("ATVPDKIKX0DER"));
        when(credentialStore.get(2L)).thenReturn(credential("A1F83G8C2ARO7P"));
        when(credentialStore.get(3L)).thenReturn(credential("A21TJRUUN4KGV"));
        when(service.sync(anyLong(), anyString(), anyInt())).thenReturn(report());

        scheduler(true).syncDaily();

        verify(service).sync(eq(1L), eq("ATVPDKIKX0DER"), eq(30));
        verify(service).sync(eq(2L), eq("A1F83G8C2ARO7P"), eq(30));
        verify(service).sync(eq(3L), eq("A21TJRUUN4KGV"), eq(30));
    }

    @Test
    @DisplayName("缺 marketplaceId 的店铺跳过并点名，不静默空转")
    void shopWithoutMarketplaceIsSkippedLoudly() {
        when(credentialStore.getActiveShopIds()).thenReturn(new LinkedHashSet<>(List.of(1L, 2L)));
        when(credentialStore.get(1L)).thenReturn(credential("ATVPDKIKX0DER"));
        when(credentialStore.get(2L)).thenReturn(credential(null));
        when(service.sync(anyLong(), anyString(), anyInt())).thenReturn(report());

        scheduler(true).syncDaily();

        verify(service).sync(eq(1L), eq("ATVPDKIKX0DER"), anyInt());
        verify(service, never()).sync(eq(2L), any(), anyInt());
    }

    @Test
    @DisplayName("单店异常不中断整轮，其余店铺照常同步")
    void oneShopFailureDoesNotAbortTheRound() {
        when(credentialStore.getActiveShopIds()).thenReturn(new LinkedHashSet<>(List.of(1L, 2L)));
        when(credentialStore.get(1L)).thenReturn(credential("ATVPDKIKX0DER"));
        when(credentialStore.get(2L)).thenReturn(credential("ATVPDKIKX0DER"));
        when(service.sync(eq(1L), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("报表生成失败"));
        when(service.sync(eq(2L), anyString(), anyInt())).thenReturn(report());

        scheduler(true).syncDaily();

        // 关键：第二家店必须真的被调用过
        verify(service).sync(eq(2L), eq("ATVPDKIKX0DER"), anyInt());
    }

    @Test
    @DisplayName("用幂等降级入口而非严格锁：upsert 覆盖语义允许 Redis 不可用时继续")
    void usesIdempotentLock() {
        when(credentialStore.getActiveShopIds()).thenReturn(new LinkedHashSet<>(List.of(1L)));
        when(credentialStore.get(1L)).thenReturn(credential("ATVPDKIKX0DER"));
        when(service.sync(anyLong(), anyString(), anyInt())).thenReturn(report());

        scheduler(true).syncDaily();

        verify(lock).runIdempotentWithLock(anyString(), anyLong(),
                any(java.util.function.Supplier.class), any());
        verify(lock, never()).runWithLock(anyString(), anyLong(),
                any(java.util.function.Supplier.class), any());
    }

    @Test
    @DisplayName("无活跃店铺：直接跳过，不抛错")
    void noShopsIsNotAnError() {
        when(credentialStore.getActiveShopIds()).thenReturn(new LinkedHashSet<>());

        scheduler(true).syncDaily();

        verify(service, never()).sync(anyLong(), anyString(), anyInt());
    }

    private static ShopCredential credential(String marketplaceId) {
        ShopCredential credential = new ShopCredential();
        credential.setMarketplaceId(marketplaceId);
        return credential;
    }

    /**
     * 构造一个同步结果。<b>不求真实数据，只求非 null</b>：调度器只读它的
     * {@code getReportId()} / {@code getRowErrors()} 打日志，因此这里用 mock 而不是
     * {@code new SyncReport(...)}——后者的构造器是包可见的（{@code SyncReport} 只能由
     * {@code SalesHistorySyncService} 自己创建，这是刻意的封装边界，不为测试放宽）。
     */
    private static SalesHistorySyncService.SyncReport report() {
        // 不额外 stub getRowErrors()：Mockito 对 List 返回值默认给空列表，
        // 而在 thenReturn(...) 里再套一层 when(...) 会触发 UnfinishedStubbing。
        return mock(SalesHistorySyncService.SyncReport.class);
    }
}
