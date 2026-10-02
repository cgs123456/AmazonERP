package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.exception.InvalidParamException;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.BidSchedule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 分时调价规则的控制面契约。
 *
 * <p>分时调价是全系统唯一会把动作下发到广告账号的通道
 * （{@code BidScheduleExecutor} 每小时按「基准价 × 倍率」真实改价）。在它只能创建、
 * 不能停用/删除的世界里，一条写错的规则会一直改到有人去数据库里手动删，
 * 所以这一组的每一条都是钱相关的：
 * <ul>
 *   <li>必须能停用、能删除、能改，且都按 id 反查归属，不能只看请求体里的 shopId；</li>
 *   <li>小时窗口必须可命中：调度查询是 {@code start_hour <= h AND end_hour >= h}，
 *       不支持跨零点，所以 22→2 这种写法等于永久休眠的死规则；</li>
 *   <li>倍率必须有界：执行器只兜绝对价上下限，一条 50x 的规则和一条手滑写错的规则
 *       在它眼里没有区别。</li>
 * </ul>
 */
@DisplayName("分时调价控制面契约")
class BidScheduleControlTest {

    private AdServiceImpl service;
    private BidScheduleMapper mapper;

    @BeforeEach
    void setUp() {
        service = new AdServiceImpl();
        mapper = mock(BidScheduleMapper.class);
        ReflectionTestUtils.setField(service, "bidScheduleMapper", mapper);
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    // ==================== 归属 ====================

    @Test
    @DisplayName("停用他店规则时拒绝且不写库")
    void toggleRejectsForeignSchedule() {
        when(mapper.selectById(9L)).thenReturn(schedule(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.toggleBidSchedule(9L, false));
        verify(mapper, never()).updateById(any(BidSchedule.class));
    }

    @Test
    @DisplayName("删除他店规则时拒绝且不删除")
    void deleteRejectsForeignSchedule() {
        when(mapper.selectById(9L)).thenReturn(schedule(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.deleteBidSchedule(9L));
        verify(mapper, never()).deleteById(9L);
    }

    @Test
    @DisplayName("规则 id 不存在时按拒绝处理，不能凭 id 造一条新规则")
    void missingScheduleFailsClosed() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThrows(CodeErrorException.class, () -> service.deleteBidSchedule(9L));
        assertThrows(CodeErrorException.class, () -> service.toggleBidSchedule(9L, true));
        verify(mapper, never()).deleteById(9L);
        verify(mapper, never()).updateById(any(BidSchedule.class));
    }

    @Test
    @DisplayName("停用本店规则会把 enabled 置 0 并落库")
    void toggleOwnSchedulePersists() {
        when(mapper.selectById(9L)).thenReturn(schedule(9L, 1L));
        when(mapper.updateById(any(BidSchedule.class))).thenReturn(1);

        assertEquals(Boolean.TRUE, service.toggleBidSchedule(9L, false));
        org.mockito.ArgumentCaptor<BidSchedule> captor =
                org.mockito.ArgumentCaptor.forClass(BidSchedule.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(Integer.valueOf(0), captor.getValue().getEnabled());
    }

    @Test
    @DisplayName("改自己店的规则可以删除")
    void deleteOwnSchedulePersists() {
        when(mapper.selectById(9L)).thenReturn(schedule(9L, 1L));

        assertEquals(Boolean.TRUE, service.deleteBidSchedule(9L));
        verify(mapper).deleteById(9L);
    }

    @Test
    @DisplayName("更新不许把规则挪到别的店铺，也不许用请求体的 shopId 绕过归属")
    void updateRejectsShopMoveAndForeignBody() {
        when(mapper.selectById(9L)).thenReturn(schedule(9L, 1L));
        BidSchedule move = schedule(9L, 2L);
        move.setMultiplier(new BigDecimal("1.10"));

        assertThrows(CodeErrorException.class, () -> service.updateBidSchedule(move));
        verify(mapper, never()).updateById(any(BidSchedule.class));

        when(mapper.selectById(10L)).thenReturn(schedule(10L, 2L));
        BidSchedule patch = schedule(10L, 1L);
        assertThrows(CodeErrorException.class, () -> service.updateBidSchedule(patch));
    }

    @Test
    @DisplayName("局部更新按合并后的形态校验：只改起始小时也要看得到与现有结束小时的冲突")
    void updateValidatesMergedWindow() {
        BidSchedule existing = schedule(9L, 1L);
        existing.setStartHour(0);
        existing.setEndHour(6);
        when(mapper.selectById(9L)).thenReturn(existing);

        BidSchedule patch = new BidSchedule();
        patch.setId(9L);
        patch.setStartHour(20);

        assertThrows(InvalidParamException.class, () -> service.updateBidSchedule(patch));
        verify(mapper, never()).updateById(any(BidSchedule.class));
    }

    @Test
    @DisplayName("合法的局部更新只改倍率，店铺与未提交字段保持库里现值")
    void updateAcceptsLegalPatch() {
        BidSchedule existing = schedule(9L, 1L);
        existing.setStartHour(0);
        existing.setEndHour(6);
        existing.setMultiplier(new BigDecimal("0.70"));
        when(mapper.selectById(9L)).thenReturn(existing);
        when(mapper.updateById(any(BidSchedule.class))).thenReturn(1);

        BidSchedule patch = new BidSchedule();
        patch.setId(9L);
        patch.setMultiplier(new BigDecimal("0.90"));

        BidSchedule saved = service.updateBidSchedule(patch);
        assertEquals(Long.valueOf(1L), saved.getShopId());
        verify(mapper).updateById(patch);
    }

    // ==================== 创建校验 ====================

    @Test
    @DisplayName("V1 种子里的三种写法都必须仍然能建")
    void createAcceptsSeedShapes() {
        when(mapper.insert(any(BidSchedule.class))).thenReturn(1);

        BidSchedule saved = service.createBidSchedule(schedule(1L, 1L));
        assertEquals(Integer.valueOf(1), saved.getEnabled());
        verify(mapper).insert(any(BidSchedule.class));
    }

    @Test
    @DisplayName("小时不在 0-23 内时拒绝：调度查询永远不会命中")
    void createRejectsHourOutOfRange() {
        BidSchedule bad = schedule(1L, 1L);
        bad.setStartHour(24);
        assertThrows(InvalidParamException.class, () -> service.createBidSchedule(bad));

        BidSchedule negative = schedule(1L, 1L);
        negative.setEndHour(-1);
        assertThrows(InvalidParamException.class, () -> service.createBidSchedule(negative));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("跨零点窗口被拒绝：查询是 start<=h AND end>=h，22→2 等于一条死规则")
    void createRejectsWindowCrossingMidnight() {
        BidSchedule bad = schedule(1L, 1L);
        bad.setStartHour(22);
        bad.setEndHour(2);

        assertThrows(InvalidParamException.class, () -> service.createBidSchedule(bad));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("倍率必须落在 0.10-5.00：执行器只兜绝对价，认不出手滑写的 50 倍")
    void createRejectsOutOfRangeMultiplier() {
        BidSchedule zero = schedule(1L, 1L);
        zero.setMultiplier(BigDecimal.ZERO);
        assertThrows(InvalidParamException.class, () -> service.createBidSchedule(zero));

        BidSchedule huge = schedule(1L, 1L);
        huge.setMultiplier(new BigDecimal("50"));
        assertThrows(InvalidParamException.class, () -> service.createBidSchedule(huge));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("缺小时或缺倍率时拒绝：两者都是 NOT NULL")
    void createRejectsMissingWindowOrMultiplier() {
        BidSchedule noHour = schedule(1L, 1L);
        noHour.setEndHour(null);
        assertThrows(InvalidParamException.class, () -> service.createBidSchedule(noHour));

        BidSchedule noMultiplier = schedule(1L, 1L);
        noMultiplier.setMultiplier(null);
        assertThrows(InvalidParamException.class, () -> service.createBidSchedule(noMultiplier));
    }

    @Test
    @DisplayName("请求体里带他店 shopId 时先按越权拒绝，不进入校验分支")
    void createRejectsForeignShopInBody() {
        BidSchedule foreign = schedule(null, 2L);

        assertThrows(CodeErrorException.class, () -> service.createBidSchedule(foreign));
        verifyNoInteractions(mapper);
    }

    private static BidSchedule schedule(Long id, Long shopId) {
        BidSchedule value = new BidSchedule();
        value.setId(id);
        value.setShopId(shopId);
        value.setCampaignId(null);
        value.setStartHour(20);
        value.setEndHour(23);
        value.setMultiplier(new BigDecimal("1.50"));
        return value;
    }
}
