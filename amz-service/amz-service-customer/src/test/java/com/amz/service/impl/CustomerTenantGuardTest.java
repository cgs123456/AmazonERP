package com.amz.service.impl;

import com.amz.classifier.TicketClassifier;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.CustomerTicketMapper;
import com.amz.mapper.ReviewSolicitationMapper;
import com.amz.model.CustomerTicket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客服工单租户守卫测试。
 * <p>
 * 工单的 shopId 在请求体/行上而不在参数里，{@code @ShopScoped} 切面拦不到，
 * 归属校验只能按行做。此前 receiveMessage/replyTicket 都没有任何归属判定：
 * 任何登录用户可以回复/关闭任意店铺的工单，也可以向任意店铺灌工单。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客服工单租户守卫：按行 shopId 拦截，拒绝话术不泄露存在性")
class CustomerTenantGuardTest {

    @Mock
    private CustomerTicketMapper ticketMapper;

    @Mock
    private ReviewSolicitationMapper solicitationMapper;

    @Mock
    private TicketClassifier classifier;

    @Mock
    private Environment environment;

    @InjectMocks
    private CustomerServiceImpl customerService;

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

    private CustomerTicket ticket(long id, long shopId) {
        CustomerTicket t = new CustomerTicket();
        t.setId(id);
        t.setShopId(shopId);
        t.setContent("商品迟迟不发货");
        return t;
    }

    @Test
    @DisplayName("创建工单：body 里的 shopId 不在授权列表 → 点名拒绝且不落库")
    void receiveMessageRejectsForeignShop() {
        CustomerTicket foreign = ticket(0L, 2L);

        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> customerService.receiveMessage(foreign));

        assertEquals("客服工单不存在或无权访问", ex.getMessage());
        verify(ticketMapper, never()).insert(any(CustomerTicket.class));
    }

    @Test
    @DisplayName("创建工单：body shopId 缺失也拒绝（isShopAllowedStrict 对 null fail-closed）")
    void receiveMessageRejectsNullShop() {
        CustomerTicket noShop = ticket(0L, 0L);
        noShop.setShopId(null);

        assertThrows(CodeErrorException.class, () -> customerService.receiveMessage(noShop));

        verify(ticketMapper, never()).insert(any(CustomerTicket.class));
    }

    @Test
    @DisplayName("创建工单：授权店铺正常完成 AI 分类并入库")
    void receiveMessageAcceptsOwnedShop() {
        TicketClassifier.Classification c = new TicketClassifier.Classification();
        c.setCategory("物流");
        c.setPriority("HIGH");
        c.setSentiment("NEGATIVE");
        when(classifier.classify(any())).thenReturn(c);
        CustomerTicket owned = ticket(0L, 1L);

        CustomerTicket saved = customerService.receiveMessage(owned);

        assertEquals("PENDING", saved.getStatus());
        verify(ticketMapper).insert(owned);
    }

    @Test
    @DisplayName("回复工单：他店工单 → 拒绝且不改任何状态")
    void replyTicketRejectsForeignTicket() {
        when(ticketMapper.selectById(9L)).thenReturn(ticket(9L, 2L));

        assertThrows(CodeErrorException.class, () -> customerService.replyTicket(9L, "抱歉"));

        verify(ticketMapper, never()).updateById(any(CustomerTicket.class));
    }

    @Test
    @DisplayName("回复工单：本人店铺工单 → 状态置 REPLIED 并落库")
    void replyTicketAcceptsOwnedTicket() {
        when(ticketMapper.selectById(9L)).thenReturn(ticket(9L, 1L));

        CustomerTicket replied = customerService.replyTicket(9L, "已催促仓库补发");

        assertEquals("REPLIED", replied.getStatus());
        verify(ticketMapper).updateById(replied);
    }
}
