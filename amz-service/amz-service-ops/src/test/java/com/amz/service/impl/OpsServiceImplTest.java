package com.amz.service.impl;

import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
import com.amz.model.HijackAlert;
import com.amz.model.KeywordRankRecord;
import com.amz.model.NegativeReviewAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营工具服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 验证差评告警处理、跟卖检测、关键词排名抓取三条核心链路。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营工具服务单元测试")
class OpsServiceImplTest {

    @Mock
    private NegativeReviewAlertMapper reviewAlertMapper;

    @Mock
    private HijackAlertMapper hijackAlertMapper;

    @Mock
    private KeywordRankRecordMapper rankMapper;

    @InjectMocks
    private OpsServiceImpl opsService;

    @Test
    @DisplayName("处理差评告警 - 告警存在 → 状态更新为 HANDLED 并返回 true")
    void testHandleNegativeReviewAlertExists() {
        NegativeReviewAlert alert = new NegativeReviewAlert();
        alert.setId(1L);
        alert.setStatus("NEW");
        when(reviewAlertMapper.selectById(1L)).thenReturn(alert);

        boolean result = opsService.handleNegativeReviewAlert(1L);

        assertTrue(result, "告警存在时应返回 true");
        assertEquals("HANDLED", alert.getStatus(), "状态应更新为 HANDLED");
        verify(reviewAlertMapper).updateById(alert);
    }

    @Test
    @DisplayName("处理差评告警 - 告警不存在 → 返回 false")
    void testHandleNegativeReviewAlertNotFound() {
        when(reviewAlertMapper.selectById(99L)).thenReturn(null);

        boolean result = opsService.handleNegativeReviewAlert(99L);

        assertFalse(result, "告警不存在时应返回 false");
        verify(reviewAlertMapper, times(0)).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("扫描差评 → 插入 1 条告警并返回 1")
    void testScanNegativeReviews() {
        int result = opsService.scanNegativeReviews(1L);

        assertEquals(1, result, "应返回 1 条告警");
        verify(reviewAlertMapper).insert(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("扫描跟卖 → 插入 1 条告警并返回 1")
    void testScanHijackers() {
        int result = opsService.scanHijackers(1L);

        assertEquals(1, result, "应返回 1 条跟卖告警");
        verify(hijackAlertMapper).insert(any(HijackAlert.class));
    }

    @Test
    @DisplayName("抓取关键词排名 → 插入 3 条记录并返回 3")
    void testCaptureKeywordRanks() {
        int result = opsService.captureKeywordRanks(1L);

        assertEquals(3, result, "应返回 3 条关键词排名记录");
        verify(rankMapper, times(3)).insert(any(KeywordRankRecord.class));
    }

    @Test
    @DisplayName("查询差评告警列表 - 按店铺和状态筛选")
    void testListNegativeReviewAlerts() {
        NegativeReviewAlert alert = new NegativeReviewAlert();
        alert.setId(1L);
        alert.setShopId(1L);
        alert.setStatus("NEW");
        when(reviewAlertMapper.selectList(any())).thenReturn(List.of(alert));

        List<NegativeReviewAlert> result = opsService.listNegativeReviewAlerts(1L, "NEW");

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getShopId());
    }
}
