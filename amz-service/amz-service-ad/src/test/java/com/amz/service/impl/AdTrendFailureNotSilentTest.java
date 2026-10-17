package com.amz.service.impl;

import com.amz.mapper.AdDailyReportMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 广告趋势查询失败不得被降级成空数组。
 *
 * <p><b>缺陷</b>：{@code AdServiceImpl.getAdTrend} 在查日报表抛异常时
 * {@code catch} 后 {@code return Collections.emptyList()}。前端拿到空数组只能
 * 显示「暂无趋势数据」——运营看到的是「没有数据」，实际是「查询失败」。
 * 两者在响应上完全一样，运营不会想到去重跑同步。
 *
 * <p><b>判据</b>：异常必须冒泡。控制器再把它转成 {@code Result.failure}，
 * 前端已有的 catch 分支会如实提示「趋势调用失败」。
 */
@DisplayName("广告趋势：查询失败不得静默返回空数组")
class AdTrendFailureNotSilentTest {

    @Test
    @DisplayName("日报表查询抛异常时必须冒泡，而不是返回空列表")
    void queryFailurePropagates() {
        AdDailyReportMapper mapper = mock(AdDailyReportMapper.class);
        when(mapper.selectList(any())).thenThrow(new RuntimeException("表未初始化"));

        AdServiceImpl service = new AdServiceImpl();
        ReflectionTestUtils.setField(service, "adDailyReportMapper", mapper);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getAdTrend(1L, 14, null));
        assertTrue(ex.getMessage().contains("查询广告日报失败"), "实际：" + ex.getMessage());
        assertTrue(ex.getCause() != null && "表未初始化".equals(ex.getCause().getMessage()),
                "原始异常必须作为 cause 保留，否则排障时看不到根因");
    }

    @Test
    @DisplayName("确实没有日报时返回空列表（这条不是失败）")
    void genuinelyEmptyReportsStillReturnEmptyList() {
        AdDailyReportMapper mapper = mock(AdDailyReportMapper.class);
        when(mapper.selectList(any())).thenReturn(java.util.List.of());

        AdServiceImpl service = new AdServiceImpl();
        ReflectionTestUtils.setField(service, "adDailyReportMapper", mapper);

        assertTrue(service.getAdTrend(1L, 14, null).isEmpty());
    }
}
