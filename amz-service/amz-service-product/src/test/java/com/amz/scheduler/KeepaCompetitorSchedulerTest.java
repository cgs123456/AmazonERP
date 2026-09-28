package com.amz.scheduler;

import com.amz.client.KeepaClient;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.CompetitorMonitorMapper;
import com.amz.model.CompetitorMonitor;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeepaCompetitorSchedulerTest {

    @BeforeAll
    static void initMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CompetitorMonitor.class);
    }

    @Mock
    private CompetitorMonitorMapper competitorMonitorMapper;

    @Mock
    private KeepaClient keepaClient;

    @Spy
    private DistributedJobLock distributedJobLock = new ImmediateDistributedJobLock();

    @InjectMocks
    private KeepaCompetitorScheduler scheduler;

    @Test
    @DisplayName("Keepa 未配置时整轮跳过，不查询监控表也不调用外部 API")
    void unavailableClientSkipsWholeRound() {
        when(keepaClient.isAvailable()).thenReturn(false);

        assertDoesNotThrow(scheduler::pollCompetitorPrices);

        verify(competitorMonitorMapper, never()).selectList(any());
        verify(keepaClient, never()).getCompetitorAnalysis(any(), any(Integer.class));
    }

    @Test
    @DisplayName("未知 marketplace 禁止静默回落到 US domain")
    void unknownMarketplaceDoesNotFallbackToUs() {
        when(keepaClient.isAvailable()).thenReturn(true);
        when(competitorMonitorMapper.selectList(any())).thenReturn(List.of(
                monitor(1L, "B000UNKNOWN", "UNKNOWN_MARKETPLACE")));

        assertDoesNotThrow(scheduler::pollCompetitorPrices);

        verify(keepaClient, never()).getCompetitorAnalysis(any(), any(Integer.class));
    }

    @Test
    @DisplayName("同一 ASIN 在不同 marketplace 必须按站点分别取数，不能共用 US 缓存")
    void sameAsinDifferentMarketplacesUseDifferentDomains() {
        when(keepaClient.isAvailable()).thenReturn(true);
        when(competitorMonitorMapper.selectList(any())).thenReturn(List.of(
                monitor(1L, "B000SHARED", "ATVPDKIKX0DER"),
                monitor(2L, "B000SHARED", "A1F83G8C2AR0N7E")));
        when(keepaClient.getCompetitorAnalysis("B000SHARED", 1)).thenReturn(validStats("US"));
        when(keepaClient.getCompetitorAnalysis("B000SHARED", 2)).thenReturn(validStats("UK"));
        when(competitorMonitorMapper.insert(any(CompetitorMonitor.class))).thenReturn(1);

        assertDoesNotThrow(scheduler::pollCompetitorPrices);

        verify(keepaClient).getCompetitorAnalysis("B000SHARED", 1);
        verify(keepaClient).getCompetitorAnalysis("B000SHARED", 2);
    }

    @Test
    @DisplayName("单个 ASIN 调用失败不能中断其余 ASIN 的取数")
    void oneAsinFailureDoesNotAbortRound() {
        when(keepaClient.isAvailable()).thenReturn(true);
        when(competitorMonitorMapper.selectList(any())).thenReturn(List.of(
                monitor(1L, "B000FAIL", "ATVPDKIKX0DER"),
                monitor(1L, "B000OKAY", "ATVPDKIKX0DER")));
        when(keepaClient.getCompetitorAnalysis("B000FAIL", 1))
                .thenThrow(new RuntimeException("upstream timeout"));
        when(keepaClient.getCompetitorAnalysis("B000OKAY", 1)).thenReturn(validStats("OK"));
        when(competitorMonitorMapper.insert(any(CompetitorMonitor.class))).thenReturn(1);

        assertDoesNotThrow(scheduler::pollCompetitorPrices);

        verify(keepaClient).getCompetitorAnalysis("B000OKAY", 1);
    }

    private static CompetitorMonitor monitor(Long shopId, String asin, String marketplaceId) {
        CompetitorMonitor monitor = new CompetitorMonitor();
        monitor.setShopId(shopId);
        monitor.setCompetitorAsin(asin);
        monitor.setMarketplaceId(marketplaceId);
        return monitor;
    }

    private static String validStats(String title) {
        return "{\"products\":[{\"title\":\"" + title + "\",\"stats\":{\"current\":"
                + "[null,1234,null,5555,null,null,null,null,null,null,null,null,null,null,null,null,12,0,4500]}}]}";
    }

    /**
     * 调度器测试关注业务动作本身；锁的抢锁/降级/fail-closed 语义由
     * amz-common 的 DistributedJobLockTest 独立覆盖。
     */
    private static class ImmediateDistributedJobLock extends DistributedJobLock {
        @Override
        public <T> T runWithLock(String lockKey, long leaseSeconds, Supplier<T> action, T fallback) {
            return action.get();
        }
    }
}
