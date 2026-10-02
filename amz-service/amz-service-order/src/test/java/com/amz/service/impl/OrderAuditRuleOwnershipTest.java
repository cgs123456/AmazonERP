package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.OrderAuditRuleMapper;
import com.amz.mapper.OrderMapper;
import com.amz.mapper.OrderSplitLogMapper;
import com.amz.mapper.ShipmentRoutingMapper;
import com.amz.model.OrderAuditRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审单规则按 id 写操作的归属校验。
 * <p>
 * <b>被修的缺口</b>：{@code /order/audit/rule/{id}} 的 update/toggle/delete 只有 id，
 * shopId 既不在 PathVariable 也不在 RequestParam 里（{@code POST /rule} 还把它放在
 * {@code @RequestBody} 实体里），{@code ShopIdGuardAspect} 解析不到就整条放行，
 * 因此此前任何登录用户改 id 就能动别人店铺的规则。另外旧 {@code toggleRule}
 * 对不存在的行静默 no-op 而 Controller 仍返回 true，等于把「什么都没做」显示成「已停用」。
 */
@DisplayName("审单规则：按 id 的写操作必须校验归属")
@ExtendWith(MockitoExtension.class)
class OrderAuditRuleOwnershipTest {

    @Mock
    private OrderAuditRuleMapper orderAuditRuleMapper;
    @Mock
    private OrderSplitLogMapper orderSplitLogMapper;
    @Mock
    private ShipmentRoutingMapper shipmentRoutingMapper;
    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderAuditServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static OrderAuditRule rule(Long id, Long shopId, boolean enabled) {
        OrderAuditRule r = new OrderAuditRule();
        r.setId(id);
        r.setShopId(shopId);
        r.setRuleName("高额拦截");
        r.setRuleType("AMOUNT_CHECK");
        r.setConditionField("final_price");
        r.setConditionOp("GT");
        r.setConditionValue("500");
        r.setAction("BLOCK");
        r.setPriority(5);
        r.setEnabled(enabled);
        return r;
    }

    @Test
    @DisplayName("停用别人店铺的规则：拒绝，且不写库")
    void toggleForeignShopRuleIsRejected() {
        when(orderAuditRuleMapper.selectById(9L)).thenReturn(rule(9L, 2L, true));

        assertThrows(CodeErrorException.class, () -> service.toggleRule(9L, false));
        // selectById 是守卫自己要查的，所以只能断言「没有写」而不是「没碰过 mapper」
        verify(orderAuditRuleMapper, never()).updateById(any(OrderAuditRule.class));
    }

    @Test
    @DisplayName("停用不存在的规则：必须报错，不能静默成功")
    void toggleMissingRuleIsRejected() {
        when(orderAuditRuleMapper.selectById(404L)).thenReturn(null);

        assertThrows(CodeErrorException.class, () -> service.toggleRule(404L, false));
        verify(orderAuditRuleMapper, never()).updateById(any(OrderAuditRule.class));
    }

    @Test
    @DisplayName("停用本店铺的规则：写入的是查出来的那一行")
    void toggleOwnShopRulePersists() {
        OrderAuditRule stored = rule(1L, 1L, true);
        when(orderAuditRuleMapper.selectById(1L)).thenReturn(stored);
        ArgumentCaptor<OrderAuditRule> saved = ArgumentCaptor.forClass(OrderAuditRule.class);

        service.toggleRule(1L, false);

        verify(orderAuditRuleMapper).updateById(saved.capture());
        assertEquals(Boolean.FALSE, saved.getValue().getEnabled());
        assertEquals(1L, saved.getValue().getShopId());
    }

    @Test
    @DisplayName("删除别人店铺的规则：拒绝，一行都不删")
    void deleteForeignShopRuleIsRejected() {
        when(orderAuditRuleMapper.selectById(9L)).thenReturn(rule(9L, 2L, true));

        assertThrows(CodeErrorException.class, () -> service.deleteRule(9L));
        verify(orderAuditRuleMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除不存在的规则：报错，不再返回「删除成功」")
    void deleteMissingRuleIsRejected() {
        when(orderAuditRuleMapper.selectById(404L)).thenReturn(null);

        assertThrows(CodeErrorException.class, () -> service.deleteRule(404L));
        verify(orderAuditRuleMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除本店铺的规则：真的按 id 删")
    void deleteOwnShopRulePersists() {
        when(orderAuditRuleMapper.selectById(1L)).thenReturn(rule(1L, 1L, false));

        service.deleteRule(1L);

        verify(orderAuditRuleMapper).deleteById(1L);
    }

    @Test
    @DisplayName("越权与不存在给同一个提示，不泄露「这条 id 属于哪家店」")
    void foreignAndMissingShareOneMessage() {
        when(orderAuditRuleMapper.selectById(9L)).thenReturn(rule(9L, 2L, true));
        when(orderAuditRuleMapper.selectById(404L)).thenReturn(null);

        String foreign = assertThrows(CodeErrorException.class, () -> service.deleteRule(9L)).getMessage();
        String missing = assertThrows(CodeErrorException.class, () -> service.deleteRule(404L)).getMessage();

        assertEquals(foreign, missing);
        assertTrue(foreign.contains("不存在或无权访问"), foreign);
    }

    @Test
    @DisplayName("更新别人店铺的规则：拒绝，不写库")
    void updateForeignShopRuleIsRejected() {
        when(orderAuditRuleMapper.selectById(9L)).thenReturn(rule(9L, 2L, true));

        assertThrows(CodeErrorException.class,
                () -> service.updateRule(rule(9L, 2L, false)));
        verify(orderAuditRuleMapper, never()).updateById(any(OrderAuditRule.class));
    }

    @Test
    @DisplayName("把规则改到别的店铺名下要拒：归属变更不能靠一次 PUT")
    void updateCannotMoveRuleAcrossShops() {
        when(orderAuditRuleMapper.selectById(1L)).thenReturn(rule(1L, 1L, true));

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> service.updateRule(rule(1L, 2L, true)));
        assertTrue(ex.getMessage().contains("不允许跨店铺移动"), ex.getMessage());
        verify(orderAuditRuleMapper, never()).updateById(any(OrderAuditRule.class));
    }

    @Test
    @DisplayName("更新本店铺规则时请求体不带 shopId，也要用库里那一行的归属回写")
    void updateFillsShopIdFromStoredRow() {
        when(orderAuditRuleMapper.selectById(1L)).thenReturn(rule(1L, 1L, true));
        OrderAuditRule payload = rule(1L, null, false);
        ArgumentCaptor<OrderAuditRule> saved = ArgumentCaptor.forClass(OrderAuditRule.class);

        service.updateRule(payload);

        verify(orderAuditRuleMapper).updateById(saved.capture());
        assertEquals(1L, saved.getValue().getShopId());
    }

    @Test
    @DisplayName("给别人店铺建规则：拒绝，不插行")
    void createRuleForForeignShopIsRejected() {
        assertThrows(CodeErrorException.class, () -> service.createRule(rule(null, 2L, true)));
        verify(orderAuditRuleMapper, never()).insert(any(OrderAuditRule.class));
    }

    @Test
    @DisplayName("不带 shopId 建规则：属性缺失，不落一条无主规则")
    void createRuleWithoutShopIdIsRejected() {
        assertThrows(AttrIsNullException.class, () -> service.createRule(rule(null, null, true)));
        verify(orderAuditRuleMapper, never()).insert(any(OrderAuditRule.class));
    }

    @Test
    @DisplayName("本店铺建规则：放行并补齐默认值")
    void createRuleForOwnShopPersists() {
        OrderAuditRule payload = rule(null, 1L, true);
        payload.setPriority(null);
        payload.setEnabled(null);

        service.createRule(payload);

        verify(orderAuditRuleMapper).insert(payload);
        assertEquals(0, payload.getPriority());
        assertEquals(Boolean.TRUE, payload.getEnabled());
    }

    @Test
    @DisplayName("ADMIN 与切面语义一致，可以跨店铺处理规则")
    void adminBypassesShopList() {
        UserContext.setRole("ADMIN");
        UserContext.setShops(List.of());
        when(orderAuditRuleMapper.selectById(9L)).thenReturn(rule(9L, 2L, true));

        service.toggleRule(9L, false);

        verify(orderAuditRuleMapper).updateById(any(OrderAuditRule.class));
    }
}
