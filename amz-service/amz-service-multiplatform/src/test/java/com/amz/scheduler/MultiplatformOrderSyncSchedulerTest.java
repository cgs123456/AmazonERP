package com.amz.scheduler;

import com.amz.lock.DistributedJobLock;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.model.PlatformAccount;
import com.amz.service.impl.MultiplatformServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多平台订单定时同步调度器。
 * <p>
 * <b>为什么需要这些测试</b>：这个调度器是本模块的第一个 {@code @Scheduled}，
 * 而它做的是「批量调外部平台 + 写业务表」。三类错误都不会让编译或既有测试变红：
 * <ul>
 *   <li>默认开着 → 未配凭据的环境每 30 分钟刷一轮 ERROR；</li>
 *   <li>不用严格锁 → 多实例部署时并发双写 {@code amz_unified_order}；</li>
 *   <li>单店异常冒泡 → 一个坏账号让整轮同步中断，其余店铺永远不同步。</li>
 * </ul>
 */
@DisplayName("多平台订单同步调度：默认关、严格锁、单店失败隔离")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiplatformOrderSyncSchedulerTest {

    @Mock
    private MultiplatformServiceImpl multiplatformService;

    @Mock
    private PlatformAccountMapper platformAccountMapper;

    @Mock
    private DistributedJobLock distributedJobLock;

    private MultiplatformOrderSyncScheduler scheduler;

    /**
     * LambdaQueryWrapper 的列解析（.select/.eq/.in）依赖 MyBatis-Plus 的实体元数据缓存，
     * 那个缓存只在 Spring 容器启动时初始化。纯 Mockito 单测必须自己注册，
     * 否则抛 "can not find lambda cache for this entity"。
     * 与 AdPagingContractTest 同一做法。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformAccount.class);
    }

    @BeforeEach
    void setUp() {
        scheduler = new MultiplatformOrderSyncScheduler();
        ReflectionTestUtils.setField(scheduler, "multiplatformService", multiplatformService);
        ReflectionTestUtils.setField(scheduler, "platformAccountMapper", platformAccountMapper);
        ReflectionTestUtils.setField(scheduler, "distributedJobLock", distributedJobLock);
    }

    @Test
    @DisplayName("默认关闭：未显式开启时既不加锁也不调外部平台")
    void disabledByDefaultDoesNothing() {
        // 不设置 syncEnabled → 保持字段默认值 false
        scheduler.syncOrders();

        verify(distributedJobLock, never()).runWithLock(anyString(), anyLong(), any(), any());
        verify(multiplatformService, never()).syncAllPlatforms(anyLong());
    }

    @Test
    @DisplayName("开启后用严格锁（不是允许降级的幂等入口）")
    void usesStrictLock() {
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);
        when(platformAccountMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());

        scheduler.syncOrders();

        // 严格版：拿不到锁就跳过，不允许 Redis 不可用时降级为并发执行
        verify(distributedJobLock).runWithLock(anyString(), anyLong(), any(), any());
        verify(distributedJobLock, never())
                .runIdempotentWithLock(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("店铺集合 = ACTIVE 账号店铺 ∪ 显式配置店铺，且去重")
    void resolvesUnionOfActiveAccountsAndConfiguredShops() {
        when(platformAccountMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(
                account(1L), account(2L), account(1L)));
        ReflectionTestUtils.setField(scheduler, "configuredShopIds", "2,3, 4");
        when(multiplatformService.syncAllPlatforms(anyLong()))
                .thenReturn(new MultiplatformServiceImpl.OrderSyncSummary(3, 3, 0, 0, List.of()));

        MultiplatformOrderSyncScheduler.SyncSummary summary = scheduler.doSyncOrders();

        assertEquals(4, summary.shops(), "1/2 来自 ACTIVE 账号（去重），3/4 来自配置");
        verify(multiplatformService).syncAllPlatforms(1L);
        verify(multiplatformService).syncAllPlatforms(2L);
        verify(multiplatformService).syncAllPlatforms(3L);
        verify(multiplatformService).syncAllPlatforms(4L);
    }

    @Test
    @DisplayName("单店抛异常不中断整轮，其余店铺照常同步")
    void oneShopFailureDoesNotAbortTheRound() {
        when(platformAccountMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(account(1L), account(2L)));
        when(multiplatformService.syncAllPlatforms(1L))
                .thenThrow(new IllegalStateException("凭证失效"));
        when(multiplatformService.syncAllPlatforms(2L))
                .thenReturn(new MultiplatformServiceImpl.OrderSyncSummary(3, 3, 0, 7, List.of()));

        MultiplatformOrderSyncScheduler.SyncSummary summary = scheduler.doSyncOrders();

        assertEquals(2, summary.shops());
        assertEquals(1, summary.succeeded());
        assertEquals(1, summary.failed());
        assertEquals(7, summary.inserted(), "第二家店的入库行数必须被计入");
        verify(multiplatformService).syncAllPlatforms(2L);
    }

    @Test
    @DisplayName("平台级失败只记日志，不当作整店失败")
    void platformLevelFailureIsNotShopFailure() {
        when(platformAccountMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(account(1L)));
        when(multiplatformService.syncAllPlatforms(1L)).thenReturn(
                new MultiplatformServiceImpl.OrderSyncSummary(3, 2, 1, 5, List.of("SHEIN")));

        MultiplatformOrderSyncScheduler.SyncSummary summary = scheduler.doSyncOrders();

        assertEquals(1, summary.succeeded(), "有平台失败但店铺整体仍算成功");
        assertEquals(0, summary.failed());
        assertEquals(5, summary.inserted());
    }

    @Test
    @DisplayName("账号查询失败必须响亮抛出，不能静默空转")
    void accountQueryFailureIsLoud() {
        when(platformAccountMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> scheduler.doSyncOrders());
    }

    @Test
    @DisplayName("无任何店铺：返回零汇总且不调用同步")
    void noShopsIsNotAnError() {
        when(platformAccountMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        ReflectionTestUtils.setField(scheduler, "configuredShopIds", "");

        MultiplatformOrderSyncScheduler.SyncSummary summary = scheduler.doSyncOrders();

        assertEquals(0, summary.shops());
        verify(multiplatformService, never()).syncAllPlatforms(anyLong());
    }

    @Test
    @DisplayName("README 语义：锁键按全局而非按店铺（避免同店被两个实例同时拉）")
    void lockKeyIsGlobal() {
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);
        when(platformAccountMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        AtomicBoolean keyCaptured = new AtomicBoolean(false);

        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            assertTrue(key.startsWith("amz:sched:multiplatform-order-sync:"), "锁键前缀：" + key);
            keyCaptured.set(true);
            return null;
        }).when(distributedJobLock).runWithLock(anyString(), anyLong(), any(), any());

        scheduler.syncOrders();

        assertTrue(keyCaptured.get(), "必须真的走过加锁入口");
    }

    private static PlatformAccount account(Long shopId) {
        PlatformAccount account = new PlatformAccount();
        account.setShopId(shopId);
        account.setStatus("ACTIVE");
        account.setPlatform("TEMU");
        return account;
    }
}
