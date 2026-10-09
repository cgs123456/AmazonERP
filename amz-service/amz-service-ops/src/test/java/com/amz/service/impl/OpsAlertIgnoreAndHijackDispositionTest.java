package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
import com.amz.model.HijackAlert;
import com.amz.model.NegativeReviewAlert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警三态终态机的补齐：IGNORED 与跟卖告警的处置路径。
 *
 * <p>动因：V1 DDL 把两张告警表的 status 声明为 {@code NEW/HANDLED/IGNORED}，但代码里
 * {@code IGNORED} 只出现在列注释与实体 javadoc，没有任何写入路径——差评只能 NEW→HANDLED，
 * 跟卖连处置端点都没有（只有造数 insert 与只读 list）。运营在界面上看见跟卖告警却无法处置。
 *
 * <p>语义（本次落地，写死在断言里）：
 * <ul>
 *   <li>HANDLED = 已介入处理；IGNORED = 判定为无需处理并关闭。两者都是终态，
 *       已处于终态的告警再次处置要报错，不做覆盖（与既有 handle 的幂等口径一致）；</li>
 *   <li>IGNORED <strong>不</strong>做去重/抑制：扫描端目前是 mock 造数，接真实数据源后
 *       是否按 (shop_id, asin) 抑制重建属于扫描侧职责，不应塞进处置侧，
 *       否则现在无法验证、将来还会和造数逻辑打架；</li>
 *   <li>归属判定复用 handle 的严格档：不存在与越权同文案，无上下文 fail-closed，
 *       shop_id 为 null 按脏数据拒绝。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警处置：IGNORED 终态与跟卖告警处置路径")
class OpsAlertIgnoreAndHijackDispositionTest {

    @Mock
    private NegativeReviewAlertMapper reviewAlertMapper;
    @Mock
    private HijackAlertMapper hijackAlertMapper;
    @Mock
    private KeywordRankRecordMapper rankMapper;

    @InjectMocks
    private OpsServiceImpl service;

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    private static NegativeReviewAlert review(long id, Long shopId, String status) {
        NegativeReviewAlert a = new NegativeReviewAlert();
        a.setId(id);
        a.setShopId(shopId);
        a.setAsin("B0TESTASIN");
        a.setStatus(status);
        return a;
    }

    private static HijackAlert hijack(long id, Long shopId, String status) {
        HijackAlert a = new HijackAlert();
        a.setId(id);
        a.setShopId(shopId);
        a.setAsin("B0TESTASIN");
        a.setStatus(status);
        return a;
    }

    // ---------- 差评：IGNORED ----------

    @Test
    @DisplayName("本店铺的新差评告警可以忽略，状态落到 IGNORED")
    void ignoreNegativeReviewMovesToIgnored() {
        NegativeReviewAlert a = review(21L, 1L, "NEW");
        when(reviewAlertMapper.selectById(21L)).thenReturn(a);

        assertTrue(service.ignoreNegativeReviewAlert(21L));

        assertEquals("IGNORED", a.getStatus());
        verify(reviewAlertMapper).updateById(a);
    }

    @Test
    @DisplayName("忽略别人店铺的差评告警：不写库并报错")
    void ignoreForeignReviewIsRejected() {
        when(reviewAlertMapper.selectById(22L)).thenReturn(review(22L, 99L, "NEW"));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.ignoreNegativeReviewAlert(22L));
        assertEquals("差评告警不存在或无权访问", ex.getMessage());
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("忽略不存在的差评告警：文案与越权一致，不做存在性探针")
    void ignoreMissingReviewUsesTheSameMessage() {
        when(reviewAlertMapper.selectById(23L)).thenReturn(null);

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.ignoreNegativeReviewAlert(23L));
        assertEquals("差评告警不存在或无权访问", ex.getMessage());
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("忽略时 shop_id 为 NULL 按脏数据拒绝，不是免检")
    void ignoreNullShopOwnerIsRejected() {
        when(reviewAlertMapper.selectById(24L)).thenReturn(review(24L, null, "NEW"));

        assertThrows(CodeErrorException.class, () -> service.ignoreNegativeReviewAlert(24L));
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("已忽略的告警不能再处理成 HANDLED：终态互斥")
    void ignoredAlertCannotBeHandled() {
        when(reviewAlertMapper.selectById(25L)).thenReturn(review(25L, 1L, "IGNORED"));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.handleNegativeReviewAlert(25L));
        assertEquals("该告警已经是 IGNORED，没有再次处理", ex.getMessage());
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("已处理的告警不能再忽略：终态互斥")
    void handledAlertCannotBeIgnored() {
        when(reviewAlertMapper.selectById(26L)).thenReturn(review(26L, 1L, "HANDLED"));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.ignoreNegativeReviewAlert(26L));
        assertEquals("该告警已经是 HANDLED，没有再次处理", ex.getMessage());
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("忽略在无认证上下文时 fail-closed")
    void ignoreWithoutContextFailsClosed() {
        UserContext.clear();
        when(reviewAlertMapper.selectById(27L)).thenReturn(review(27L, 1L, "NEW"));

        assertThrows(CodeErrorException.class, () -> service.ignoreNegativeReviewAlert(27L));
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    // ---------- 跟卖：HANDLED / IGNORED ----------

    @Test
    @DisplayName("本店铺的新跟卖告警可以标记已处理")
    void handleHijackMovesToHandled() {
        HijackAlert a = hijack(31L, 1L, "NEW");
        when(hijackAlertMapper.selectById(31L)).thenReturn(a);

        assertTrue(service.handleHijackAlert(31L));

        assertEquals("HANDLED", a.getStatus());
        verify(hijackAlertMapper).updateById(a);
    }

    @Test
    @DisplayName("本店铺的新跟卖告警可以忽略")
    void ignoreHijackMovesToIgnored() {
        HijackAlert a = hijack(32L, 1L, "NEW");
        when(hijackAlertMapper.selectById(32L)).thenReturn(a);

        assertTrue(service.ignoreHijackAlert(32L));

        assertEquals("IGNORED", a.getStatus());
        verify(hijackAlertMapper).updateById(a);
    }

    @Test
    @DisplayName("处理别人店铺的跟卖告警：不写库并报错")
    void handleForeignHijackIsRejected() {
        when(hijackAlertMapper.selectById(33L)).thenReturn(hijack(33L, 99L, "NEW"));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.handleHijackAlert(33L));
        assertEquals("跟卖告警不存在或无权访问", ex.getMessage());
        verify(hijackAlertMapper, never()).updateById(any(HijackAlert.class));
    }

    @Test
    @DisplayName("跟卖告警不存在与越权同文案，不做存在性探针")
    void hijackMissingUsesTheSameMessage() {
        when(hijackAlertMapper.selectById(34L)).thenReturn(null);

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.ignoreHijackAlert(34L));
        assertEquals("跟卖告警不存在或无权访问", ex.getMessage());
        verify(hijackAlertMapper, never()).updateById(any(HijackAlert.class));
    }

    @Test
    @DisplayName("跟卖告警 shop_id 为 NULL 按脏数据拒绝")
    void hijackNullShopOwnerIsRejected() {
        when(hijackAlertMapper.selectById(35L)).thenReturn(hijack(35L, null, "NEW"));

        assertThrows(CodeErrorException.class, () -> service.handleHijackAlert(35L));
        verify(hijackAlertMapper, never()).updateById(any(HijackAlert.class));
    }

    @Test
    @DisplayName("跟卖告警已是终态时拒绝再次处置")
    void hijackTerminalStateIsRejected() {
        when(hijackAlertMapper.selectById(36L)).thenReturn(hijack(36L, 1L, "IGNORED"));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.handleHijackAlert(36L));
        assertEquals("该告警已经是 IGNORED，没有再次处理", ex.getMessage());
        verify(hijackAlertMapper, never()).updateById(any(HijackAlert.class));
    }

    @Test
    @DisplayName("跟卖处置在无认证上下文时 fail-closed")
    void hijackWithoutContextFailsClosed() {
        UserContext.clear();
        when(hijackAlertMapper.selectById(37L)).thenReturn(hijack(37L, 1L, "NEW"));

        assertThrows(CodeErrorException.class, () -> service.handleHijackAlert(37L));
        verify(hijackAlertMapper, never()).updateById(any(HijackAlert.class));
    }

    @Test
    @DisplayName("ADMIN 不受店铺清单限制，可处置任意店铺的跟卖告警")
    void adminHandlesAnyHijack() {
        UserContext.setRole("ADMIN");
        HijackAlert a = hijack(38L, 5L, "NEW");
        when(hijackAlertMapper.selectById(38L)).thenReturn(a);

        assertTrue(service.handleHijackAlert(38L));
        assertEquals("HANDLED", a.getStatus());
    }
}