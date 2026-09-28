package com.amz.service.impl;

import com.amz.classifier.TicketClassifier;
import com.amz.mapper.CustomerTicketMapper;
import com.amz.mapper.EmailTemplateMapper;
import com.amz.mapper.NegativeReviewMapper;
import com.amz.mapper.ReviewSolicitationMapper;
import com.amz.mapper.RmaMapper;
import com.amz.model.CustomerTicket;
import com.amz.model.EmailTemplate;
import com.amz.model.NegativeReview;
import com.amz.model.ReviewSolicitation;
import com.amz.model.Rma;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客服模块无界列表回归：五个公开列表必须统一使用 keyset 分页，
 * 并显式返回截断/下一页游标，不能静默加载全表。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客服列表 keyset 分页契约")
class CustomerPagingContractTest {

    @Mock
    private EmailTemplateMapper emailTemplateMapper;

    @Mock
    private NegativeReviewMapper negativeReviewMapper;

    @Mock
    private RmaMapper rmaMapper;

    @Mock
    private CustomerTicketMapper ticketMapper;

    @Mock
    private ReviewSolicitationMapper solicitationMapper;

    @Mock
    private TicketClassifier classifier;

    @Mock
    private Environment environment;

    @InjectMocks
    private CustomerEmailServiceImpl emailService;

    @InjectMocks
    private CustomerServiceImpl customerService;

    @BeforeAll
    static void initMybatisTableInfo() {
        register(EmailTemplate.class);
        register(NegativeReview.class);
        register(Rma.class);
        register(CustomerTicket.class);
        register(ReviewSolicitation.class);
    }

    private static void register(Class<?> entityType) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                entityType);
    }

    @Test
    @DisplayName("邮件模板：游标翻页、探测行不泄漏且返回 nextCursor")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listTemplatesUsesKeysetPage() {
        when(emailTemplateMapper.selectList(any())).thenReturn(List.of(
                emailTemplate(9L), emailTemplate(8L), emailTemplate(7L)));

        PageResult<EmailTemplate> page = emailService.listTemplates(
                1L, "ORDER", PageRequest.of(2, PageRequest.encodeCursor(10L)));

        assertEquals(List.of(9L, 8L), page.items().stream().map(EmailTemplate::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(8L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<EmailTemplate>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(emailTemplateMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("id <"), "缺少 keyset 游标条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("差评列表：必须带显式 LIMIT，空尾页不得伪造 nextCursor")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listNegativeReviewsIsBounded() {
        when(negativeReviewMapper.selectList(any())).thenReturn(List.of(
                negativeReview(5L), negativeReview(4L)));

        PageResult<NegativeReview> page = emailService.listNegativeReviews(
                1L, "DETECTED", 2, PageRequest.first(2));

        assertEquals(2, page.items().size());
        assertFalse(page.truncated());
        assertEquals(null, page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<NegativeReview>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(negativeReviewMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("LIMIT 3"));
    }

    @Test
    @DisplayName("RMA 列表：必须带显式 LIMIT 并暴露截断事实")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listRmasIsBounded() {
        when(rmaMapper.selectList(any())).thenReturn(List.of(
                rma(12L), rma(11L), rma(10L)));

        PageResult<Rma> page = emailService.listRmas(1L, "PENDING", PageRequest.first(2));

        assertEquals(List.of(12L, 11L), page.items().stream().map(Rma::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(11L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<Rma>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(rmaMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("LIMIT 3"));
    }

    @Test
    @DisplayName("工单列表：游标翻页、探测行不泄漏且返回 nextCursor")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listTicketsUsesKeysetPage() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(
                ticket(20L), ticket(19L), ticket(18L)));

        PageResult<CustomerTicket> page = customerService.listTickets(
                1L, "PENDING", "SHIPPING", PageRequest.of(2, PageRequest.encodeCursor(21L)));

        assertEquals(List.of(20L, 19L), page.items().stream().map(CustomerTicket::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(19L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<CustomerTicket>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ticketMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("id <"), "缺少 keyset 游标条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("索评列表：必须带显式 LIMIT，空尾页不得伪造 nextCursor")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listSolicitationsIsBounded() {
        when(solicitationMapper.selectList(any())).thenReturn(List.of(solicitation(3L)));

        PageResult<ReviewSolicitation> page = customerService.listSolicitations(
                1L, PageRequest.first(2));

        assertEquals(1, page.items().size());
        assertFalse(page.truncated());
        assertEquals(null, page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<ReviewSolicitation>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(solicitationMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("LIMIT 3"));
    }

    private static EmailTemplate emailTemplate(Long id) {
        EmailTemplate value = new EmailTemplate();
        value.setId(id);
        value.setShopId(1L);
        return value;
    }

    private static NegativeReview negativeReview(Long id) {
        NegativeReview value = new NegativeReview();
        value.setId(id);
        value.setShopId(1L);
        return value;
    }

    private static Rma rma(Long id) {
        Rma value = new Rma();
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

    private static ReviewSolicitation solicitation(Long id) {
        ReviewSolicitation value = new ReviewSolicitation();
        value.setId(id);
        value.setShopId(1L);
        return value;
    }
}