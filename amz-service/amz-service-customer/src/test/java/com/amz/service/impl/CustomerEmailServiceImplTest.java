package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.EmailTaskMapper;
import com.amz.mapper.EmailTemplateMapper;
import com.amz.mapper.NegativeReviewMapper;
import com.amz.mapper.RmaMapper;
import com.amz.model.EmailTemplate;
import com.amz.model.NegativeReview;
import com.amz.model.Rma;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
