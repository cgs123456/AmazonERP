package com.amz.service.impl;

import com.amz.mapper.AmzProductMapper;
import com.amz.model.AmzProduct;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品主数据写入契约。
 * <p>
 * 关键一条：唯一键 {@code uk_shop_sku_market} 里的 {@code marketplace_id} 可空，
 * MySQL 的 UNIQUE KEY 不约束 NULL。若存在性检查用 {@code eq(null)}，条件恒不成立，
 * 每次创建都会判定为"不存在"从而重复插入——所以这里断言的是生成的 SQL 片段里
 * 出现 {@code IS NULL} 而不是 {@code = }。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("商品主数据：唯一键、店铺隔离与读取上限")
class ProductMasterServiceImplTest {

    @Mock
    private AmzProductMapper amzProductMapper;

    @InjectMocks
    private ProductMasterServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 不初始化时 wrapper.getSqlSegment() 无法解析 lambda 列名，断言会变成空比较
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AmzProduct.class);
    }

    @BeforeEach
    void stubDefaults() {
        when(amzProductMapper.selectCount(any())).thenReturn(0L);
    }

    private static AmzProduct row(Long id, Long shopId, String sku) {
        AmzProduct p = new AmzProduct();
        p.setId(id);
        p.setShopId(shopId);
        p.setSku(sku);
        p.setAsin("B0" + sku);
        p.setTitle("Yoga mat " + sku);
        p.setPrice(new BigDecimal("19.99"));
        // 经新 API 建的行必然带 marketplace（DDL NOT NULL）；负例里再显式清掉
        p.setMarketplaceId("ATVPDKIKX0DER");
        return p;
    }

    @Test
    @DisplayName("缺店铺或缺 SKU 直接拒绝，不落库")
    void createValidatesRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> service.create(new AmzProduct()));
        AmzProduct noSku = row(null, 7L, null);
        assertThrows(IllegalArgumentException.class, () -> service.create(noSku));
        verify(amzProductMapper, never()).insert(any(AmzProduct.class));
    }

    @Test
    @DisplayName("唯一键三列都按等值判重，冲突时报可读错误")
    void duplicateCheckUsesEquiJoinOnUniqueKey() {
        AmzProduct existing = row(1L, 7L, "SKU-1");
        when(amzProductMapper.selectCount(any())).thenReturn(1L);

        IllegalArgumentException exc = assertThrows(IllegalArgumentException.class,
                () -> service.create(existing));
        assertTrue(exc.getMessage().contains("已存在"), "应报重复，实际：" + exc.getMessage());

        ArgumentCaptor<LambdaQueryWrapper<AmzProduct>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(amzProductMapper).selectCount(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("IS NULL") == false,
                "marketplace 是 NOT NULL 列，不该出现 IS NULL 分支，实际：" + sql);
    }

    @Test
    @DisplayName("marketplaceId / title 缺失在这里挡掉（DDL 是 NOT NULL）")
    void createRejectsNotNullColumns() {
        AmzProduct noMarket = row(null, 7L, "SKU-NM");
        noMarket.setMarketplaceId(null);
        assertThrows(IllegalArgumentException.class, () -> service.create(noMarket));

        AmzProduct noTitle = row(null, 7L, "SKU-NT");
        noTitle.setMarketplaceId("ATVPDKIKX0DER");
        noTitle.setTitle("   ");
        assertThrows(IllegalArgumentException.class, () -> service.create(noTitle));
        verify(amzProductMapper, never()).insert(any(AmzProduct.class));
    }

    @Test
    @DisplayName("重复之外允许创建，并补默认状态与创建时间")
    void createFillsDefaultsWhenUnique() {
        AmzProduct fresh = row(null, 7L, "SKU-NEW");

        AmzProduct saved = service.create(fresh);

        assertEquals("ACTIVE", saved.getStatus());
        assertEquals("SKU-NEW", saved.getSku());
        verify(amzProductMapper).insert(saved);
    }

    @Test
    @DisplayName("别人的店铺行按不存在处理，不透露它的存在")
    void getIsShopScoped() {
        when(amzProductMapper.selectById(9L)).thenReturn(row(9L, 8L, "SKU-OTHER"));

        IllegalArgumentException exc = assertThrows(IllegalArgumentException.class,
                () -> service.get(7L, 9L));
        assertTrue(exc.getMessage().contains("不存在"), "跨店铺应报不存在，实际：" + exc.getMessage());
    }

    @Test
    @DisplayName("更新不接受改后的 SKU+站点 撞唯一键")
    void updateRejectsCollision() {
        when(amzProductMapper.selectById(9L)).thenReturn(row(9L, 7L, "SKU-OLD"));
        when(amzProductMapper.selectCount(any())).thenReturn(1L);
        AmzProduct patch = new AmzProduct();
        patch.setSku("SKU-TAKEN");

        IllegalArgumentException exc = assertThrows(IllegalArgumentException.class,
                () -> service.update(7L, 9L, patch));
        assertTrue(exc.getMessage().contains("冲突"), "实际：" + exc.getMessage());
        verify(amzProductMapper, never()).updateById(any(AmzProduct.class));
    }

    @Test
    @DisplayName("更新只覆盖给出的字段，id/shopId 不被请求体改写")
    void updateKeepsIdentityFields() {
        when(amzProductMapper.selectById(9L)).thenReturn(row(9L, 7L, "SKU-OLD"));
        AmzProduct patch = new AmzProduct();
        patch.setId(999L);
        patch.setShopId(666L);
        patch.setSku("SKU-OLD");
        patch.setSizeTier("SMALL_STANDARD");
        patch.setWeightG(430);
        patch.setBrand("Akman");
        patch.setStatus("INACTIVE");

        AmzProduct updated = service.update(7L, 9L, patch);

        assertEquals(9L, updated.getId());
        assertEquals(7L, updated.getShopId());
        assertEquals("SMALL_STANDARD", updated.getSizeTier());
        assertEquals(430, updated.getWeightG());
        assertEquals("B0SKU-OLD", updated.getAsin());
        assertEquals("INACTIVE", updated.getStatus());
        verify(amzProductMapper).updateById(updated);
    }

    @Test
    @DisplayName("列表与跨店铺查询都带上限，不做无界整表读")
    void readsAreCapped() {
        service.list(7L, null, "yoga");
        service.listByAsinAcrossShops(List.of(7L, 8L), "B0SKU");

        ArgumentCaptor<LambdaQueryWrapper<AmzProduct>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(amzProductMapper, org.mockito.Mockito.times(2)).selectList(captor.capture());
        for (LambdaQueryWrapper<AmzProduct> w : captor.getAllValues()) {
            assertTrue(w.getSqlSegment().toUpperCase().contains("LIMIT"),
                    "查询必须带 LIMIT，实际：" + w.getSqlSegment());
        }
        assertEquals(ProductMasterServiceImpl.READ_CAP, 500);
    }

    @Test
    @DisplayName("跨店铺查询必须给授权范围，且不按 asin 空值放行")
    void crossShopRequiresScope() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listByAsinAcrossShops(List.of(), "B0SKU"));
        assertThrows(IllegalArgumentException.class,
                () -> service.listByAsinAcrossShops(List.of(7L), "  "));
    }
}
