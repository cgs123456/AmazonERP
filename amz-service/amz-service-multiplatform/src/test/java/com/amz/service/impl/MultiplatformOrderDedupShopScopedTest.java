package com.amz.service.impl;

import com.amz.client.SheinClient;
import com.amz.client.TemuClient;
import com.amz.client.TikTokClient;
import com.amz.mapper.UnifiedOrderMapper;
import com.amz.model.UnifiedOrder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.amz.finance.PlatformCurrencyConverter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一订单同步的去重查询必须带 shop_id（§S33.108）。
 *
 * <p>业务前提与 §S33.91 同一个：不同店铺可能在同一平台拿到同一
 * {@code platform_order_no}（同一供货商的货分销给多家店）。
 *
 * <p>旧实现 {@code loadExistingOrderKeys} 只按
 * {@code in(platform_order_no, ...)} 查已存在的行，不区分店铺：
 * A 店同步过的订单号会让 B 店同一订单号被判「已存在」而<b>静默跳过</b>
 * ——inserted 少算、B 店的订单永远不落库，且无日志无异常，
 * 表现是「B 店拉到了但列表里没有」。
 *
 * <p>表上的唯一键 {@code uk_platform_order (platform, platform_order_no)}
 * 同样不含 shop_id，所以「跳过」顺带还掩盖了另一条路：
 * 真有两个店铺共享同一订单号时 insert 会撞 1062，
 * 整个平台的同步被标为失败（{@code syncAllPlatforms} 记 failedPlatforms）。
 *
 * <p><b>观察面选 SQL 片段</b>：只数 insert 调用证明不了查询条件里有没有 shop_id
 * ——把 shop_id 从条件里删掉、仍让 selectList 返回空，写库断言依旧全绿。
 * 所以必须有断言落在 wrapper 拼出来的 SQL 上（与 §S33.91 同一手法）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("多平台统一订单同步：去重查询必须带 shop_id，跨店不得互相判重")
class MultiplatformOrderDedupShopScopedTest {

    @Mock private TemuClient temuClient;
    @Mock private TikTokClient tiktokClient;
    @Mock private SheinClient sheinClient;
    @Mock private UnifiedOrderMapper unifiedOrderMapper;
    @Mock private PlatformCurrencyConverter currencyConverter;

    @InjectMocks private MultiplatformServiceImpl service;

    /** MP 3.5.17 起 lambda cache 依赖 mapper 注册时初始化的 TableInfo。 */
    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UnifiedOrder.class);
    }

    @Test
    @DisplayName("去重查询必须含 shop_id，否则别的店的订单号会把本店订单判成已存在")
    void dedupQueryMustBeShopScoped() {
        when(temuClient.fetchRecentOrders(7L)).thenReturn(List.of(order("TE-9001")));
        when(unifiedOrderMapper.selectList(any())).thenReturn(List.of());

        service.syncByPlatform(7L, "TEMU");

        String sql = capturedDedupQuery().getCustomSqlSegment();
        assertTrue(sql.contains("shop_id"),
                "去重查询缺少 shop_id：别的店已有的 platform_order_no 会让本店同一订单号被判已存在而跳过，"
                        + "本店订单静默不落库。实际 SQL：" + sql);
        assertTrue(sql.contains("platform_order_no"),
                "原有 platform_order_no 条件不能被删掉。实际 SQL：" + sql);
    }

    /**
     * 端到端语义：别的店有同一订单号时，本店<b>仍要 insert</b>。
     *
     * <p>与上一条互为保险：上条锁查询形状，这条锁行为结果。
     * 只留上条时有人换成手写 XML 会漏；只留这条时 selectList 的 mock
     * 语义一变它可能因别的原因变绿。
     */
    @Test
    @DisplayName("别的店已有同一订单号时，本店订单仍要入库，不被判重跳过")
    void insertsOwnOrderWhenAnotherShopHasSamePlatformOrderNo() {
        UnifiedOrder fetched = order("TE-9001");
        when(temuClient.fetchRecentOrders(7L)).thenReturn(List.of(fetched));
        // 别的店（shopId=9）已存在同一 platform_order_no
        UnifiedOrder otherShopRow = order("TE-9001");
        otherShopRow.setId(555L);
        otherShopRow.setShopId(9L);
        // 假库必须尊重查询条件：不带 shop_id 的查询会把别的店那行也捞回来，
        // 带了的则捞不到。写成 selectList(any()) 无条件返回这一行，
        // 等于假库无视 shop_id，这条测试就永远只为别的原因变红。
        when(unifiedOrderMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<?> q = invocation.getArgument(0);
            String sql = q.getCustomSqlSegment();
            return sql.contains("shop_id") ? List.of() : List.of(otherShopRow);
        });

        assertEquals(1, service.syncByPlatform(7L, "TEMU"),
                "别的店的同一订单号不能让本店订单被跳过");

        verify(unifiedOrderMapper).insert(fetched);
        assertEquals(7L, fetched.getShopId(), "入库的行必须挂在本店名下");
    }

    /**
     * 反向保险：本店已有同一订单号时<b>仍要判重</b>，不能为了修跨店问题
     * 把本店的幂等也一起改坏——否则每轮同步都 insert 一遍，唯一键会直接拦失败。
     */
    @Test
    @DisplayName("本店已有同一订单号时仍然跳过，不重复入库")
    void skipsWhenOwnShopAlreadyHasTheOrder() {
        UnifiedOrder fetched = order("TE-9001");
        when(temuClient.fetchRecentOrders(7L)).thenReturn(List.of(fetched));
        UnifiedOrder mine = order("TE-9001");
        mine.setId(4242L);
        mine.setShopId(7L);
        when(unifiedOrderMapper.selectList(any())).thenReturn(List.of(mine));

        assertEquals(0, service.syncByPlatform(7L, "TEMU"),
                "本店已有同一订单号时应判重跳过，不能重复插入");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<UnifiedOrder> capturedDedupQuery() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(unifiedOrderMapper).selectList(captor.capture());
        List<LambdaQueryWrapper> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private static UnifiedOrder order(String platformOrderNo) {
        UnifiedOrder o = new UnifiedOrder();
        o.setPlatform("TEMU");
        o.setPlatformOrderNo(platformOrderNo);
        o.setShopId(7L);
        return o;
    }
}
