package com.amz.controller;

import com.amz.model.CustomerTicket;
import com.amz.model.EmailTemplate;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.result.Result;
import com.amz.service.CustomerEmailService;
import com.amz.service.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客服列表 HTTP 契约：data 保持数组以兼容旧前端，_page 增量暴露截断与游标。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客服列表 HTTP 分页契约")
class CustomerPagingControllerContractTest {

    @Mock
    private CustomerEmailService customerEmailService;

    @Mock
    private CustomerService customerService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CustomerEmailController emailController = new CustomerEmailController();
        ReflectionTestUtils.setField(emailController, "customerEmailService", customerEmailService);

        CustomerController customerController = new CustomerController();
        ReflectionTestUtils.setField(customerController, "customerService", customerService);

        mockMvc = MockMvcBuilders
                .standaloneSetup(emailController, customerController)
                .build();
    }

    @Test
    @DisplayName("邮件模板：data 仍为数组，_page 暴露截断和 nextCursor")
    void templateListKeepsArrayAndExposesPageMeta() throws Exception {
        String cursor = PageRequest.encodeCursor(10L);
        when(customerEmailService.listTemplates(eq(1L), eq("ORDER"), argThat(
                page -> page != null && page.size() == 2 && page.hasCursor())))
                .thenReturn(PageResult.of(List.of(template(9L), template(8L), template(7L)), 2,
                        item -> PageRequest.encodeCursor(item.getId())));

        mockMvc.perform(get("/customer/email/template/list/1")
                        .param("templateType", "ORDER")
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$._page.size").value(2))
                .andExpect(jsonPath("$._page.returned").value(2))
                .andExpect(jsonPath("$._page.hasMore").value(true))
                .andExpect(jsonPath("$._page.truncated").value(true))
                .andExpect(jsonPath("$._page.nextCursor").value(PageRequest.encodeCursor(8L)))
                .andExpect(jsonPath("$.message").value(Result.MSG_TRUNCATED));

        verify(customerEmailService).listTemplates(eq(1L), eq("ORDER"), argThat(
                page -> page != null && page.size() == 2 && page.hasCursor()));
    }

    @Test
    @DisplayName("工单：data 仍为数组，_page 暴露截断和 nextCursor")
    void ticketListKeepsArrayAndExposesPageMeta() throws Exception {
        when(customerService.listTickets(eq(1L), eq("PENDING"), eq("SHIPPING"), argThat(
                page -> page != null && page.size() == 2 && page.hasCursor())))
                .thenReturn(PageResult.of(List.of(ticket(20L), ticket(19L), ticket(18L)), 2,
                        item -> PageRequest.encodeCursor(item.getId())));

        mockMvc.perform(get("/customer/ticket/list/1")
                        .param("status", "PENDING")
                        .param("category", "SHIPPING")
                        .param("size", "2")
                        .param("cursor", PageRequest.encodeCursor(21L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$._page.truncated").value(true))
                .andExpect(jsonPath("$._page.nextCursor").value(PageRequest.encodeCursor(19L)));
    }

    private static EmailTemplate template(Long id) {
        EmailTemplate value = new EmailTemplate();
        value.setId(id);
        value.setShopId(1L);
        return value;
    }

    private static CustomerTicket ticket(Long id) {
        CustomerTicket value = new CustomerTicket();
        value.setId(id);
        value.setShopId(1L);
        return value;
    }
}