package com.amz.service.impl;

import com.amz.client.SheinClient;
import com.amz.client.TemuClient;
import com.amz.client.TikTokClient;
import com.amz.mapper.PlatformMessageMapper;
import com.amz.mapper.PlatformProductMapper;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品/消息同步的<b>租户边界</b>契约（§S33.91）。
 *
 * <p>业务前提（已与用户确认）：同一平台下，不同店铺会拿到同一个
 * {@code platform_product_id} / {@code platform_message_id}——同一供货商的货分销给多家店
 * 是常见形态。
 *
 * <p>旧实现的两个 exist 查询都漏了 {@code shop_id}，命中别的店的行时：
 * <ul>
 *   <li>商品：{@code setId(exist.getId()) + updateById}，把<b>别人的行整条改写成自己店铺</b>的。
 *       原店铺的商品从自己列表里消失，它人工建的 ASIN/SKU 映射也一并易主；</li>
 *   <li>消息：命中后什么都不做。本店这条消息永远不落库，且不 insert 不 update、
 *       无日志无异常——客服看到的是「没有新消息」而不是「同步失败」。</li>
 * </ul>
 * 两处都发生在 {@code @ShopScoped + @RequireRole} 之内，<b>不是越权</b>，
 * 是租户边界漏在查询条件上。
 *
 * <p><b>观察面选 SQL 片段</b>：只数 insert/update 调用证明不了「查询条件里有没有 shop_id」
 * ——把 shop_id 从查询里删掉、仍然让 selectOne 返回 null，所有写库断言依旧全绿。
 * 所以必须有断言落在那个 wrapper 拼出来的 SQL 上（与 ad 模块
 * {@code AdTenantIsolationContractTest} 同一手法）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("多平台商品/消息同步：exist 查询必须带 shop_id，跨店不得互写")
class MultiplatformShopScopedSyncTest {

    @Mock private TemuClient temuClient;
    @Mock private TikTokClient tiktokClient;
    @Mock private SheinClient sheinClient;
    @Mock private PlatformProductMapper platformProductMapper;
    @Mock private PlatformMessageMapper platformMessageMapper;

    @InjectMocks private MultiplatformServiceImpl service;

    /**
     * MP 3.5.17 起 lambda cache 依赖 mapper 注册时初始化的 TableInfo；
     * mock 掉 mapper 后无人初始化，解析实体列会抛 "can not find lambda cache"。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        for (Class<?> entity : List.of(PlatformProduct.class, PlatformMessage.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @Test
    @DisplayName("商品同步：exist 查询必须含 shop_id，否则会拿别人的行来 updateById")
    void productExistQueryMustBeShopScoped() {
        PlatformProduct fetched = product("TEMU-PROD-1");
        when(temuClient.fetchProducts(7L)).thenReturn(List.of(fetched));
        // 本店确实没有这一行 → 必须走 insert，而不是更新别人的行
        when(platformProductMapper.selectOne(any())).thenReturn(null);

        service.syncProducts(7L, "TEMU");

        String sql = capturedProductQuery().getCustomSqlSegment();
        assertTrue(sql.contains("shop_id"),
                "exist 查询缺少 shop_id：会把 (TEMU, TEMU-PROD-1) 上别人的行认成本店的，"
                        + "接着 setId(exist.getId()) + updateById 把别人的商品改写成自己店铺的。实际 SQL：" + sql);
        assertTrue(sql.contains("platform")
                        && sql.contains("platform_product_id"),
                "除 shop_id 外，原 platform / platform_product_id 条件不能被删掉。实际 SQL：" + sql);
    }

    /**
     * 端到端语义：本店没有这一行时，无论别的店是否已有同一 platform_product_id，都要 insert。
     *
     * <p>与上一条互为保险：上条锁查询形状，这条锁行为结果。只留上条时，
     * 有人把查询换成别的等价写法（手写 XML）会漏；只留这条时，
     * selectOne 的 mock 语义一变它可能因别的原因变绿。
     */
    @Test
    @DisplayName("商品同步：本店没有这一行就必须 insert，不借用别人的行")
    void productInsertsOwnRowWhenShopHasNoSuchRow() {
        PlatformProduct fetched = product("TEMU-PROD-1");
        when(temuClient.fetchProducts(7L)).thenReturn(List.of(fetched));
        when(platformProductMapper.selectOne(any())).thenReturn(null);

        assertEquals(1, service.syncProducts(7L, "TEMU"));

        verify(platformProductMapper).insert(fetched);
        verify(platformProductMapper, never()).updateById(any(PlatformProduct.class));
    }

    @Test
    @DisplayName("消息同步：exist 查询必须含 shop_id，否则本店消息被判已存在而永不落库")
    void messageExistQueryMustBeShopScoped() {
        PlatformMessage fetched = message("TEMU-MSG-1");
        when(temuClient.fetchMessages(7L)).thenReturn(List.of(fetched));
        when(platformMessageMapper.selectOne(any())).thenReturn(null);

        service.syncMessages(7L, "TEMU");

        String sql = capturedMessageQuery().getCustomSqlSegment();
        assertTrue(sql.contains("shop_id"),
                "exist 查询缺少 shop_id：命中别的店的消息行后本方法直接跳过，既不改写也不插入，"
                        + "本店这条消息静默消失。实际 SQL：" + sql);
    }

    @Test
    @DisplayName("消息同步：本店没有这一行就必须 insert")
    void messageInsertsOwnRowWhenShopHasNoSuchRow() {
        PlatformMessage fetched = message("TEMU-MSG-1");
        when(temuClient.fetchMessages(7L)).thenReturn(List.of(fetched));
        when(platformMessageMapper.selectOne(any())).thenReturn(null);

        assertEquals(1, service.syncMessages(7L, "TEMU"));

        verify(platformMessageMapper).insert(fetched);
    }

    /**
     * 本店已有这一行时走 update 而非 insert。
     *
     * <p>这条盯的是「别为了修跨店问题把本店更新也一起改坏」：
     * shop_id 补进条件后，本店二次同步仍必须命中自己那一行，否则每轮同步
     * 都会 insert 出新行，唯一键（V4 起的 uk_shop_platform_*）会直接把同步拦失败。
     */
    @Test
    @DisplayName("商品同步：本店已有同一行时仍然命中并更新，不插一行")
    void productUpdatesOwnRowOnSecondSync() {
        PlatformProduct fetched = product("TEMU-PROD-1");
        PlatformProduct mine = product("TEMU-PROD-1");
        mine.setId(4242L);
        mine.setShopId(7L);
        mine.setAmazonAsin("B0AAAAAAAA");
        when(temuClient.fetchProducts(7L)).thenReturn(List.of(fetched));
        when(platformProductMapper.selectOne(any())).thenReturn(mine);

        assertEquals(1, service.syncProducts(7L, "TEMU"));

        assertEquals(4242L, fetched.getId(), "必须更新本店那一行，而不是插入新行");
        assertEquals("B0AAAAAAAA", fetched.getAmazonAsin(),
                "人工建立的 ASIN 映射不能被同步覆盖（同旧实现约定的保留语义）");
        verify(platformProductMapper).updateById(fetched);
        verify(platformProductMapper, never()).insert(any(PlatformProduct.class));
    }

    private LambdaQueryWrapper<PlatformProduct> capturedProductQuery() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(platformProductMapper).selectOne(captor.capture());
        List<LambdaQueryWrapper> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private LambdaQueryWrapper<PlatformMessage> capturedMessageQuery() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(platformMessageMapper).selectOne(captor.capture());
        List<LambdaQueryWrapper> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private static PlatformProduct product(String platformProductId) {
        PlatformProduct p = new PlatformProduct();
        p.setPlatformProductId(platformProductId);
        return p;
    }

    private static PlatformMessage message(String platformMessageId) {
        PlatformMessage m = new PlatformMessage();
        m.setPlatformMessageId(platformMessageId);
        return m;
    }
}
