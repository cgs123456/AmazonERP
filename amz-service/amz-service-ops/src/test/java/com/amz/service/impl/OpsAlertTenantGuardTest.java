package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.HijackAlertMapper;
import com.amz.mapper.KeywordRankRecordMapper;
import com.amz.mapper.NegativeReviewAlertMapper;
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
 * 差评告警「标记已处理」的归属判定。
 *
 * <p>动因：原实现是 {@code alert.getShopId() != null && !UserContext.isShopAllowed(...)}，
 * 两个洞都在同一行里——
 * <ul>
 *   <li>{@code != null} 让 shop_id 缺失的行直接绕过判定，而 V1 DDL 写的是
 *       {@code shop_id BIGINT NOT NULL}：真出现 null 就是脏数据，不该当成「谁的都不是、谁都能改」；</li>
 *   <li>{@code isShopAllowed} 是宽松档（没有上下文时放行并计数），而这条端点上没有
 *       {@code @ShopScoped}——{@code alertId} 不是 shopId，切面解析不到，服务内的逐行判定
 *       是唯一一道防线，必须用严格档。</li>
 * </ul>
 *
 * <p>越权与不存在返回同一句文案，否则这个端点会退化成「告警 ID 是否存在」的探针。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("差评告警处理归属判定")
class OpsAlertTenantGuardTest {

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

    private static NegativeReviewAlert alert(long id, Long shopId, String status) {
        NegativeReviewAlert a = new NegativeReviewAlert();
        a.setId(id);
        a.setShopId(shopId);
        a.setAsin("B0TESTASIN");
        a.setStatus(status);
        return a;
    }

    @Test
    @DisplayName("本店铺的新告警可以标记已处理")
    void handledWhenAlertBelongsToAuthorizedShop() {
        NegativeReviewAlert a = alert(11L, 1L, "NEW");
        when(reviewAlertMapper.selectById(11L)).thenReturn(a);

        assertTrue(service.handleNegativeReviewAlert(11L));

        assertEquals("HANDLED", a.getStatus());
        verify(reviewAlertMapper).updateById(a);
    }

    @Test
    @DisplayName("别人店铺的告警：既不写库也报错，而不是静默返回 false")
    void foreignShopAlertIsRejected() {
        when(reviewAlertMapper.selectById(12L)).thenReturn(alert(12L, 99L, "NEW"));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.handleNegativeReviewAlert(12L));
        assertEquals("差评告警不存在或无权访问", ex.getMessage());
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("不存在的告警与越权的告警文案一致，不做存在性探针")
    void missingAlertUsesTheSameMessageAsForeignOne() {
        when(reviewAlertMapper.selectById(13L)).thenReturn(null);

        CodeErrorException missing = assertThrows(CodeErrorException.class,
                () -> service.handleNegativeReviewAlert(13L));
        assertEquals("差评告警不存在或无权访问", missing.getMessage());
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("shop_id 为 NULL 的行按脏数据拒绝：DDL 是 NOT NULL，不该反过来当免检")
    void nullShopOwnerIsNotACheckBypass() {
        when(reviewAlertMapper.selectById(14L)).thenReturn(alert(14L, null, "NEW"));

        assertThrows(CodeErrorException.class, () -> service.handleNegativeReviewAlert(14L));
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("完全没有认证上下文时 fail-closed，不能因为「没登录信息」放行")
    void withoutContextFailsClosed() {
        UserContext.clear();
        when(reviewAlertMapper.selectById(15L)).thenReturn(alert(15L, 1L, "NEW"));

        assertThrows(CodeErrorException.class, () -> service.handleNegativeReviewAlert(15L));
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("已处理过的告警再点一次要幂等报错，不覆盖成第二个 HANDLED")
    void alreadyHandledAlertIsRejected() {
        when(reviewAlertMapper.selectById(16L)).thenReturn(alert(16L, 1L, "HANDLED"));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.handleNegativeReviewAlert(16L));
        assertEquals("该告警已经是 HANDLED，没有再次处理", ex.getMessage());
        verify(reviewAlertMapper, never()).updateById(any(NegativeReviewAlert.class));
    }

    @Test
    @DisplayName("ADMIN 不受店铺清单限制")
    void adminHandlesAnyShop() {
        UserContext.setRole("ADMIN");
        NegativeReviewAlert a = alert(17L, 5L, "NEW");
        when(reviewAlertMapper.selectById(17L)).thenReturn(a);

        assertTrue(service.handleNegativeReviewAlert(17L));
        assertEquals("HANDLED", a.getStatus());
    }
}
