package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.mapper.AdCreativeMapper;
import com.amz.mapper.AdTargetingMapper;
import com.amz.model.AdCampaignExt;
import com.amz.model.AdCreative;
import com.amz.model.AdTargeting;
import com.amz.result.PageRequest;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 广告多租户隔离契约。
 *
 * <p>广告扩展接口此前只校验请求参数是否存在，没有校验素材、定向、活动记录本身
 * 是否属于该 shop。攻击者只要拥有任意一个合法店铺，就可以构造另一个店铺的
 * campaignId / 资源 id 进行读取或修改。本测试要求服务层在读写前完成资源归属校验，
 * 且批量状态更新 SQL 必须带 shop_id 条件。
 */
@DisplayName("广告多租户隔离契约")
class AdTenantIsolationContractTest {

    @BeforeAll
    static void initMybatisTableInfo() {
        register(AdCampaignExt.class);
        register(AdCreative.class);
        register(AdTargeting.class);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("活动列表拒绝未授权店铺且不执行查询")
    void campaignListRejectsUnauthorizedShop() {
        AdCampaignExtMapper mapper = mock(AdCampaignExtMapper.class);
        AdCampaignExtServiceImpl service = campaignService(mapper);
        authorize(1L);

        assertThrows(CodeErrorException.class,
                () -> service.listCampaigns(2L, null, PageRequest.first(50)));

        verify(mapper, never()).selectList(any());
    }

    @Test
    @DisplayName("活动创建拒绝请求体与授权店铺不一致，不能偷偷改写归属")
    void campaignCreateRejectsBodyShopMismatch() {
        AdCampaignExtMapper mapper = mock(AdCampaignExtMapper.class);
        AdCampaignExtServiceImpl service = campaignService(mapper);
        authorize(1L);

        AdCampaignExt campaign = campaign(2L, "camp-other");
        assertThrows(CodeErrorException.class, () -> service.createCampaign(1L, campaign));

        verify(mapper, never()).insert(any(AdCampaignExt.class));
    }

    @Test
    @DisplayName("活动更新拒绝改写已有记录的店铺归属")
    void campaignUpdateRejectsCrossShopExistingRecord() {
        AdCampaignExtMapper mapper = mock(AdCampaignExtMapper.class);
        AdCampaignExtServiceImpl service = campaignService(mapper);
        authorize(1L);

        AdCampaignExt existing = campaign(2L, "camp-other");
        existing.setId(10L);
        AdCampaignExt update = campaign(1L, "camp-other");
        update.setId(10L);
        when(mapper.selectById(10L)).thenReturn(existing);

        assertThrows(CodeErrorException.class, () -> service.updateCampaign(1L, update));

        verify(mapper, never()).updateById(any(AdCampaignExt.class));
    }

    @Test
    @DisplayName("批量更新状态必须把 shop_id 下推到 SQL，避免按 id 跨店更新")
    void batchUpdateStatusPushesShopScopeIntoMapper() {
        AdCampaignExtMapper mapper = mock(AdCampaignExtMapper.class);
        AdCampaignExtServiceImpl service = campaignService(mapper);
        authorize(1L);
        List<Long> ids = List.of(10L, 11L);
        when(mapper.batchUpdateStatusByIds(1L, ids, "PAUSED")).thenReturn(2);
        when(mapper.selectList(any())).thenReturn(List.of(campaign(1L, "camp-a")));

        service.batchUpdateStatus(1L, ids, "PAUSED");

        verify(mapper).batchUpdateStatusByIds(1L, ids, "PAUSED");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<AdCampaignExt>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("shop_id"), "查询回显必须限定 shop_id：" + sql);
    }

    @Test
    @DisplayName("素材创建拒绝挂到其他店铺的活动")
    void creativeCreateRejectsCampaignFromOtherShop() {
        AdCampaignExtMapper campaignMapper = mock(AdCampaignExtMapper.class);
        AdCreativeMapper creativeMapper = mock(AdCreativeMapper.class);
        AdCreativeServiceImpl service = creativeService(campaignMapper, creativeMapper);
        authorize(1L);
        when(campaignMapper.selectOne(any())).thenReturn(null);

        AdCreative creative = creative(null, "camp-other");
        assertThrows(CodeErrorException.class, () -> service.createCreative(1L, creative));

        verify(creativeMapper, never()).insert(any(AdCreative.class));
    }

    @Test
    @DisplayName("素材列表同时按 shop_id 和 campaign_id 过滤")
    void creativeListScopesByShopAndCampaign() {
        AdCampaignExtMapper campaignMapper = mock(AdCampaignExtMapper.class);
        AdCreativeMapper creativeMapper = mock(AdCreativeMapper.class);
        AdCreativeServiceImpl service = creativeService(campaignMapper, creativeMapper);
        authorize(1L);
        when(campaignMapper.selectOne(any())).thenReturn(campaign(1L, "camp-a"));
        when(creativeMapper.selectList(any())).thenReturn(List.of(creative(1L, "camp-a")));

        service.listByCampaign(1L, "camp-a", PageRequest.first(20));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<AdCreative>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(creativeMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("shop_id"), "素材查询缺少 shop_id：" + sql);
        assertTrue(sql.contains("campaign_id"), "素材查询缺少 campaign_id：" + sql);
    }

    @Test
    @DisplayName("素材审核拒绝其他店铺的资源")
    void creativeReviewRejectsCrossShopResource() {
        AdCampaignExtMapper campaignMapper = mock(AdCampaignExtMapper.class);
        AdCreativeMapper creativeMapper = mock(AdCreativeMapper.class);
        AdCreativeServiceImpl service = creativeService(campaignMapper, creativeMapper);
        authorize(1L);
        AdCreative existing = creative(10L, "camp-other");
        existing.setShopId(2L);
        when(creativeMapper.selectById(10L)).thenReturn(existing);

        assertThrows(CodeErrorException.class,
                () -> service.review(1L, 10L, "APPROVED"));

        verify(creativeMapper, never()).updateById(any(AdCreative.class));
    }

    @Test
    @DisplayName("定向删除拒绝其他店铺的资源")
    void targetingDeleteRejectsCrossShopResource() {
        AdCampaignExtMapper campaignMapper = mock(AdCampaignExtMapper.class);
        AdTargetingMapper targetingMapper = mock(AdTargetingMapper.class);
        AdTargetingServiceImpl service = targetingService(campaignMapper, targetingMapper);
        authorize(1L);
        AdTargeting existing = targeting(10L, "camp-other");
        existing.setShopId(2L);
        when(targetingMapper.selectById(10L)).thenReturn(existing);

        assertThrows(CodeErrorException.class, () -> service.delete(1L, 10L));

        verify(targetingMapper, never()).deleteById(anyLong());
        verify(targetingMapper, never()).delete(any());
    }

    @Test
    @DisplayName("素材写接口在未授权店铺时不得先查资源")
    void creativeWritesRejectUnauthorizedShopBeforeRead() {
        AdCampaignExtMapper campaignMapper = mock(AdCampaignExtMapper.class);
        AdCreativeMapper creativeMapper = mock(AdCreativeMapper.class);
        AdCreativeServiceImpl service = creativeService(campaignMapper, creativeMapper);
        authorize(1L);

        AdCreative update = creative(10L, "camp-a");
        assertThrows(CodeErrorException.class, () -> service.updateCreative(2L, update));
        assertThrows(CodeErrorException.class, () -> service.review(2L, 10L, "APPROVED"));

        verify(creativeMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("定向写接口在未授权店铺时不得先查资源")
    void targetingWritesRejectUnauthorizedShopBeforeRead() {
        AdCampaignExtMapper campaignMapper = mock(AdCampaignExtMapper.class);
        AdTargetingMapper targetingMapper = mock(AdTargetingMapper.class);
        AdTargetingServiceImpl service = targetingService(campaignMapper, targetingMapper);
        authorize(1L);

        AdTargeting update = targeting(10L, "camp-a");
        assertThrows(CodeErrorException.class, () -> service.updateTargeting(2L, update));
        assertThrows(CodeErrorException.class, () -> service.delete(2L, 10L));

        verify(targetingMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("店铺ID为空时在访问数据库前拒绝")
    void nullShopIdFailsClosed() {
        AdCampaignExtMapper mapper = mock(AdCampaignExtMapper.class);
        AdCampaignExtServiceImpl service = campaignService(mapper);
        authorize(1L);

        assertThrows(AttrIsNullException.class,
                () -> service.listCampaigns(null, null, PageRequest.first(20)));

        verify(mapper, never()).selectList(any());
    }

    private static AdCampaignExtServiceImpl campaignService(AdCampaignExtMapper mapper) {
        AdCampaignExtServiceImpl service = new AdCampaignExtServiceImpl();
        AdTenantGuard guard = guard(mapper);
        ReflectionTestUtils.setField(service, "campaignExtMapper", mapper);
        ReflectionTestUtils.setField(service, "tenantGuard", guard);
        return service;
    }

    private static AdCreativeServiceImpl creativeService(AdCampaignExtMapper campaignMapper,
                                                          AdCreativeMapper creativeMapper) {
        AdCreativeServiceImpl service = new AdCreativeServiceImpl();
        ReflectionTestUtils.setField(service, "adCreativeMapper", creativeMapper);
        ReflectionTestUtils.setField(service, "tenantGuard", guard(campaignMapper));
        return service;
    }

    private static AdTargetingServiceImpl targetingService(AdCampaignExtMapper campaignMapper,
                                                            AdTargetingMapper targetingMapper) {
        AdTargetingServiceImpl service = new AdTargetingServiceImpl();
        ReflectionTestUtils.setField(service, "adTargetingMapper", targetingMapper);
        ReflectionTestUtils.setField(service, "tenantGuard", guard(campaignMapper));
        return service;
    }

    private static AdTenantGuard guard(AdCampaignExtMapper mapper) {
        AdTenantGuard guard = new AdTenantGuard();
        ReflectionTestUtils.setField(guard, "campaignExtMapper", mapper);
        return guard;
    }

    private static void authorize(Long... shops) {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(shops));
    }

    private static AdCampaignExt campaign(Long shopId, String campaignId) {
        AdCampaignExt value = new AdCampaignExt();
        value.setShopId(shopId);
        value.setCampaignId(campaignId);
        value.setCampaignName(campaignId);
        value.setAdType("SP");
        value.setStatus("ENABLED");
        return value;
    }

    private static AdCreative creative(Long id, String campaignId) {
        AdCreative value = new AdCreative();
        value.setId(id);
        value.setCampaignId(campaignId);
        value.setStatus("PENDING");
        return value;
    }

    private static AdTargeting targeting(Long id, String campaignId) {
        AdTargeting value = new AdTargeting();
        value.setId(id);
        value.setCampaignId(campaignId);
        value.setTargetingType("CONTEXTUAL");
        return value;
    }

    private static void register(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}