package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.EmailTaskMapper;
import com.amz.mapper.EmailTemplateMapper;
import com.amz.mapper.NegativeReviewMapper;
import com.amz.mapper.RmaMapper;
import com.amz.model.EmailTask;
import com.amz.model.EmailTemplate;
import com.amz.model.NegativeReview;
import com.amz.model.Rma;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客服邮件服务多租户越权测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 回归：模板/启停/RMA 按 ID 操作曾无店铺归属校验，且对应端点仅携带 ID、
 * ShopScoped 切面按参数名找不到 shopId 而跳过，形成 IDOR。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客服邮件多租户越权测试")
class CustomerEmailServiceImplTest {

    @Mock
    private EmailTemplateMapper emailTemplateMapper;

    @Mock
    private EmailTaskMapper emailTaskMapper;

    @Mock
    private NegativeReviewMapper negativeReviewMapper;

    @Mock
    private RmaMapper rmaMapper;

    @Mock
    private Environment environment;

    @InjectMocks
    private CustomerEmailServiceImpl emailService;

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    /**
     * 纯 Mockito 测试没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时会抛
     * “can not find lambda cache for this entity”。手动注册表元信息后，
     * 分页断言才能检查真实拼出来的 SQL 片段。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                EmailTask.class);
    }

    // ---------------- 待发邮件批次 / 邮件任务列表：截断必须可见 ----------------

    @Test
    @DisplayName("processPendingEmails：批次触顶必须回传 hasMorePending=true，且探测行不进入本批处理")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void processPendingEmailsReportsBacklog() {
        // 1001 条 = 批次上限 1000 + 1 条探测行
        List<EmailTask> batch = new ArrayList<>();
        for (long i = 1; i <= 1001; i++) {
            batch.add(emailTask(i));
        }
        when(emailTaskMapper.selectList(any())).thenReturn(batch);

        Map<String, Object> result = emailService.processPendingEmails(1L);

        assertEquals(Boolean.TRUE, result.get("hasMorePending"),
                "命中批次上限却报 hasMorePending=false，会把「本批做完」误读成「积压已清空」");
        assertEquals(1000, result.get("batchSize"), "探测行只用于判定积压，不能进入本批处理");
        assertEquals(1000, result.get("batchLimit"));
        ArgumentCaptor<LambdaQueryWrapper<EmailTask>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(emailTaskMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("LIMIT"),
                "批次查询必须带显式 LIMIT，不允许无界 selectList");
    }

    @Test
    @DisplayName("processPendingEmails：未触顶时 hasMorePending=false")
    void processPendingEmailsWithoutBacklog() {
        when(emailTaskMapper.selectList(any())).thenReturn(List.of(emailTask(1L), emailTask(2L)));

        Map<String, Object> result = emailService.processPendingEmails(1L);

        assertEquals(Boolean.FALSE, result.get("hasMorePending"));
        assertEquals(2, result.get("batchSize"));
    }

    @Test
    @DisplayName("listEmailTasks：原无界 selectList 改为带 LIMIT 的 keyset 分页，探测行不泄漏")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listEmailTasksPaged() {
        when(emailTaskMapper.selectList(any()))
                .thenReturn(List.of(emailTask(2L), emailTask(1L)));

        PageResult<EmailTask> page =
                emailService.listEmailTasks(1L, "PENDING", PageRequest.first(1));

        assertEquals(1, page.items().size(), "第 size+1 行只用于判定 hasMore");
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(2L), page.nextCursor(), "游标取本页最后一条可见行，不是被丢弃的探测行");
        ArgumentCaptor<LambdaQueryWrapper<EmailTask>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(emailTaskMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("LIMIT"),
                "邮件任务列表此前完全无界，必须补上显式 LIMIT");
    }

    @Test
    @DisplayName("listEmailTasks：未取满一页时 truncated=false")
    void listEmailTasksNotTruncated() {
        when(emailTaskMapper.selectList(any())).thenReturn(List.of(emailTask(1L)));

        PageResult<EmailTask> page =
                emailService.listEmailTasks(1L, null, PageRequest.first(50));

        assertFalse(page.truncated());
        assertEquals(1, page.items().size());
    }

    private static EmailTask emailTask(Long id) {
        EmailTask t = new EmailTask();
        t.setId(id);
        t.setShopId(1L);
        t.setAmazonOrderId("O-" + id);
        t.setStatus("PENDING");
        return t;
    }

    @Test
    @DisplayName("toggleTemplate：非授权店铺应拒绝")
    void toggleTemplateDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));
        EmailTemplate template = new EmailTemplate();
        template.setId(5L);
        template.setShopId(99L);
        when(emailTemplateMapper.selectById(5L)).thenReturn(template);

        assertThrows(IllegalStateException.class, () -> emailService.toggleTemplate(5L, true));
    }

    @Test
    @DisplayName("toggleTemplate：授权店铺放行")
    void toggleTemplateAllowedForOwnShop() {
        UserContext.setShops(List.of(1L));
        EmailTemplate template = new EmailTemplate();
        template.setId(5L);
        template.setShopId(1L);
        when(emailTemplateMapper.selectById(5L)).thenReturn(template);

        assertTrue(emailService.toggleTemplate(5L, false));
    }

    @Test
    @DisplayName("getRma/updateRmaStatus：非授权店铺应拒绝")
    void rmaDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));
        Rma rma = new Rma();
        rma.setId(9L);
        rma.setShopId(99L);
        when(rmaMapper.selectById(9L)).thenReturn(rma);

        assertThrows(IllegalStateException.class, () -> emailService.getRma(9L));
        assertThrows(IllegalStateException.class, () -> emailService.updateRmaStatus(9L, "RESOLVED"));
    }

    @Test
    @DisplayName("updateTemplate：请求体 shopId 不得搬移模板归属")
    void updateTemplateLocksShopId() {
        UserContext.setShops(List.of(1L));
        EmailTemplate existed = new EmailTemplate();
        existed.setId(5L);
        existed.setShopId(1L);
        when(emailTemplateMapper.selectById(5L)).thenReturn(existed);

        EmailTemplate input = new EmailTemplate();
        input.setId(5L);
        input.setShopId(99L); // 伪造归属
        input.setTemplateName("x");
        emailService.updateTemplate(input);

        org.junit.jupiter.api.Assertions.assertEquals(1L, input.getShopId());
    }

    @Test
    @DisplayName("match/followup：非授权店铺的差评应拒绝")
    void reviewDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));
        NegativeReview review = new NegativeReview();
        review.setId(11L);
        review.setShopId(99L);
        when(negativeReviewMapper.selectById(11L)).thenReturn(review);

        assertThrows(IllegalStateException.class, () -> emailService.matchNegativeReviewToOrder(11L));
        assertThrows(IllegalStateException.class, () -> emailService.followUpNegativeReview(11L));
    }

    @Test
    @DisplayName("match：不存在的差评应报不存在而非越权")
    void reviewMissingThrowsNotFound() {
        UserContext.setShops(List.of(1L));
        when(negativeReviewMapper.selectById(12L)).thenReturn(null);

        assertThrows(AttrIsNullException.class, () -> emailService.matchNegativeReviewToOrder(12L));
    }

    @Test
    @DisplayName("create系入口：非授权店铺应拒绝写入")
    void createDeniedForOtherShop() {
        UserContext.setShops(List.of(1L));

        EmailTemplate template = new EmailTemplate();
        template.setShopId(99L);
        template.setTemplateName("t");
        template.setBody("b");
        assertThrows(IllegalStateException.class, () -> emailService.createTemplate(template));

        com.amz.model.EmailTask task = new com.amz.model.EmailTask();
        task.setShopId(99L);
        task.setAmazonOrderId("O1");
        assertThrows(IllegalStateException.class, () -> emailService.createEmailTaskManually(task));

        NegativeReview review = new NegativeReview();
        review.setShopId(99L);
        review.setAsin("B1");
        review.setReviewRating(1);
        assertThrows(IllegalStateException.class, () -> emailService.saveNegativeReview(review));

        Rma rma = new Rma();
        rma.setShopId(99L);
        rma.setAmazonOrderId("O1");
        assertThrows(IllegalStateException.class, () -> emailService.createRma(rma));
    }
}
