package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.EmailTemplateMapper;
import com.amz.mapper.EmailTaskMapper;
import com.amz.mapper.NegativeReviewMapper;
import com.amz.mapper.RmaMapper;
import com.amz.model.*;
import com.amz.service.CustomerEmailService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 客服管理升级服务实现。
 * <p>
 * 邮件模板 → 自动化邮件 → 差评监控 → RMA 退货全流程。
 * <p>
 * 模拟数据治理：邮件真实发送与差评-订单匹配依赖外部通道（Amazon Messaging / SP-API），
 * 非 mock 环境下相关方法<b>诚实失败</b>，不再伪造 SENT/MATCHED 状态误导运营。
 */
@Slf4j
@Service
public class CustomerEmailServiceImpl implements CustomerEmailService {

    @Autowired
    private EmailTemplateMapper emailTemplateMapper;

    @Autowired
    private EmailTaskMapper emailTaskMapper;

    @Autowired
    private NegativeReviewMapper negativeReviewMapper;

    @Autowired
    private RmaMapper rmaMapper;

    @Autowired
    private Environment environment;

    /** 是否运行在 mock profile。 */
    private boolean isMockProfile() {
        try {
            for (String p : environment.getActiveProfiles()) {
                if ("mock".equals(p)) {
                    return true;
                }
            }
        } catch (Exception ignore) {
            // 无环境上下文时按非 mock 处理（诚实失败）
        }
        return false;
    }

    // ==================== 邮件模板 ====================

    @Override
    public EmailTemplate createTemplate(EmailTemplate template) {
        if (template.getShopId() == null || template.getTemplateName() == null || template.getBody() == null) {
            throw new AttrIsNullException("店铺ID、模板名称和正文不能为空");
        }
        if (template.getEnabled() == null) template.setEnabled(1);
        if (template.getTriggerDelayHours() == null) template.setTriggerDelayHours(0);
        if (template.getLanguage() == null) template.setLanguage("en");
        emailTemplateMapper.insert(template);
        return template;
    }

    @Override
    public EmailTemplate updateTemplate(EmailTemplate template) {
        if (template.getId() == null) {
            throw new AttrIsNullException("模板ID不能为空");
        }
        emailTemplateMapper.updateById(template);
        return template;
    }

    @Override
    public List<EmailTemplate> listTemplates(Long shopId, String templateType) {
        LambdaQueryWrapper<EmailTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmailTemplate::getShopId, shopId);
        if (templateType != null && !templateType.isBlank()) {
            wrapper.eq(EmailTemplate::getTemplateType, templateType);
        }
        wrapper.orderByDesc(EmailTemplate::getId);
        return emailTemplateMapper.selectList(wrapper);
    }

    @Override
    public boolean toggleTemplate(Long templateId, boolean enabled) {
        EmailTemplate template = emailTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new AttrIsNullException("模板不存在：id=" + templateId);
        }
        template.setEnabled(enabled ? 1 : 0);
        emailTemplateMapper.updateById(template);
        return true;
    }

    // ==================== 自动化邮件 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EmailTask triggerEmailByEvent(Long shopId, String eventType, String orderId, String asin,
                                         String buyerEmail, String buyerName, String trackingNo) {
        // 查找匹配事件类型的启用模板
        LambdaQueryWrapper<EmailTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmailTemplate::getShopId, shopId)
               .eq(EmailTemplate::getTriggerEvent, eventType)
               .eq(EmailTemplate::getEnabled, 1);
        List<EmailTemplate> templates = emailTemplateMapper.selectList(wrapper);

        if (templates.isEmpty()) {
            log.info("无匹配的邮件模板：shopId={}, event={}", shopId, eventType);
            return null;
        }

        EmailTemplate template = templates.get(0);

        // 渲染模板变量
        String subject = renderTemplate(template.getSubject(), orderId, asin, buyerName, trackingNo);
        String body = renderTemplate(template.getBody(), orderId, asin, buyerName, trackingNo);

        EmailTask task = new EmailTask();
        task.setShopId(shopId);
        task.setTemplateId(template.getId());
        task.setAmazonOrderId(orderId);
        task.setAsin(asin);
        task.setBuyerEmail(buyerEmail);
        task.setBuyerName(buyerName);
        task.setSubject(subject);
        task.setBody(body);
        task.setStatus("PENDING");
        task.setSource("AUTO");

        // 计算计划发送时间（当前时间 + 延迟小时数）
        LocalDateTime scheduledTime = LocalDateTime.now().plusHours(template.getTriggerDelayHours());
        task.setScheduledTime(scheduledTime);

        emailTaskMapper.insert(task);
        log.info("自动化邮件任务已创建：shopId={}, event={}, orderId={}, scheduled={}", shopId, eventType, orderId, scheduledTime);
        return task;
    }

    @Override
    public Map<String, Object> processPendingEmails(Long shopId) {
        LambdaQueryWrapper<EmailTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmailTask::getShopId, shopId)
               .eq(EmailTask::getStatus, "PENDING")
               .le(EmailTask::getScheduledTime, LocalDateTime.now());
        List<EmailTask> pendingTasks = emailTaskMapper.selectList(wrapper);

        int sent = 0;
        int failed = 0;
        List<Map<String, Object>> failures = new ArrayList<>();

        for (EmailTask task : pendingTasks) {
            try {
                if (!isMockProfile()) {
                    // 非 mock：无真实发送通道时诚实标记失败，绝不伪造 SENT
                    task.setStatus("FAILED");
                    task.setFailureReason("邮件发送通道未接入（Amazon Messaging/SMTP），非 mock 环境");
                    emailTaskMapper.updateById(task);
                    failed++;
                    Map<String, Object> failure = new LinkedHashMap<>();
                    failure.put("taskId", task.getId());
                    failure.put("orderId", task.getAmazonOrderId());
                    failure.put("error", task.getFailureReason());
                    failures.add(failure);
                    log.warn("邮件发送通道未接入，任务标记 FAILED：taskId={}", task.getId());
                    continue;
                }
                // mock 环境：模拟发送成功（日志明确标注 MOCK）
                log.warn("[MOCK] 模拟邮件发送成功：taskId={}, orderId={}（非真实发送）",
                        task.getId(), task.getAmazonOrderId());
                task.setStatus("SENT");
                task.setSentTime(LocalDateTime.now());
                emailTaskMapper.updateById(task);
                sent++;
            } catch (Exception e) {
                task.setStatus("FAILED");
                task.setFailureReason(e.getMessage());
                emailTaskMapper.updateById(task);
                failed++;

                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("taskId", task.getId());
                failure.put("orderId", task.getAmazonOrderId());
                failure.put("error", e.getMessage());
                failures.add(failure);
                log.error("邮件发送失败：taskId={}", task.getId(), e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("totalPending", pendingTasks.size());
        result.put("sent", sent);
        result.put("failed", failed);
        result.put("failures", failures);
        return result;
    }

    @Override
    public List<EmailTask> listEmailTasks(Long shopId, String status) {
        LambdaQueryWrapper<EmailTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmailTask::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(EmailTask::getStatus, status);
        }
        wrapper.orderByDesc(EmailTask::getId);
        return emailTaskMapper.selectList(wrapper);
    }

    @Override
    public EmailTask createEmailTaskManually(EmailTask task) {
        if (task.getShopId() == null || task.getAmazonOrderId() == null) {
            throw new AttrIsNullException("店铺ID和订单号不能为空");
        }
        task.setSource("MANUAL");
        if (task.getStatus() == null) task.setStatus("PENDING");
        if (task.getScheduledTime() == null) task.setScheduledTime(LocalDateTime.now());
        emailTaskMapper.insert(task);
        return task;
    }

    // ==================== 差评监控 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NegativeReview saveNegativeReview(NegativeReview review) {
        if (review.getShopId() == null || review.getAsin() == null || review.getReviewRating() == null) {
            throw new AttrIsNullException("店铺ID、ASIN和评分不能为空");
        }
        review.setStatus("DETECTED");
        if (review.getVerifiedPurchase() == null) review.setVerifiedPurchase(0);
        negativeReviewMapper.insert(review);
        log.info("差评已记录：shopId={}, asin={}, rating={}", review.getShopId(), review.getAsin(), review.getReviewRating());
        return review;
    }

    @Override
    public List<NegativeReview> listNegativeReviews(Long shopId, String status, Integer minRating) {
        LambdaQueryWrapper<NegativeReview> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NegativeReview::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(NegativeReview::getStatus, status);
        }
        if (minRating != null) {
            wrapper.le(NegativeReview::getReviewRating, minRating);
        }
        wrapper.orderByDesc(NegativeReview::getId);
        return negativeReviewMapper.selectList(wrapper);
    }

    @Override
    public Map<String, Object> matchNegativeReviewToOrder(Long reviewId) {
        NegativeReview review = negativeReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new AttrIsNullException("差评记录不存在：id=" + reviewId);
        }

        // 真实匹配需通过 SP-API 查询该 ASIN 在留评日期前 7-30 天的订单。
        // 非 mock 环境诚实失败：伪造订单号落库为 MATCHED 会误导后续索评/跟进流程
        if (!isMockProfile()) {
            throw new IllegalStateException(
                    "差评订单匹配依赖 SP-API 订单查询，尚未接入真实实现（本地演示请启用 mock profile），reviewId=" + reviewId);
        }
        String matchedOrderId = "SIMULATED-MATCH-" + System.currentTimeMillis();
        log.warn("[MOCK] 差评订单匹配为模拟数据：reviewId={}, orderId={}（非真实匹配）", reviewId, matchedOrderId);

        review.setMatchedOrderId(matchedOrderId);
        review.setStatus("MATCHED");
        negativeReviewMapper.updateById(review);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviewId", reviewId);
        result.put("asin", review.getAsin());
        result.put("reviewRating", review.getReviewRating());
        result.put("matchedOrderId", matchedOrderId);
        result.put("status", "MATCHED");
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EmailTask followUpNegativeReview(Long reviewId) {
        NegativeReview review = negativeReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new AttrIsNullException("差评记录不存在：id=" + reviewId);
        }
        if (!"MATCHED".equals(review.getStatus()) && !"DETECTED".equals(review.getStatus())) {
            throw new IllegalStateException("仅 DETECTED/MATCHED 状态的差评可跟进，当前状态：" + review.getStatus());
        }

        // 查找差评跟进模板：同类型可能配置多条启用模板，selectOne 多命中会抛
        // TooManyResultsException —— 固定取 id 最小的一条，保证确定性
        LambdaQueryWrapper<EmailTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmailTemplate::getShopId, review.getShopId())
               .eq(EmailTemplate::getTemplateType, "NEGATIVE_REVIEW_FOLLOWUP")
               .eq(EmailTemplate::getEnabled, 1)
               .orderByAsc(EmailTemplate::getId)
               .last("LIMIT 1");
        EmailTemplate template = emailTemplateMapper.selectOne(wrapper);

        if (template == null) {
            log.warn("未找到差评跟进邮件模板：shopId={}", review.getShopId());
            return null;
        }

        String orderId = review.getMatchedOrderId() != null ? review.getMatchedOrderId() : review.getAmazonOrderId();
        String subject = renderTemplate(template.getSubject(), orderId, review.getAsin(), review.getReviewerName(), null);
        String body = renderTemplate(template.getBody(), orderId, review.getAsin(), review.getReviewerName(), null);

        EmailTask task = new EmailTask();
        task.setShopId(review.getShopId());
        task.setTemplateId(template.getId());
        task.setAmazonOrderId(orderId);
        task.setAsin(review.getAsin());
        task.setSubject(subject);
        task.setBody(body);
        task.setStatus("PENDING");
        task.setSource("AUTO");
        task.setScheduledTime(LocalDateTime.now());
        emailTaskMapper.insert(task);

        // 更新差评状态
        review.setStatus("CONTACTED");
        review.setContactEmailTaskId(task.getId());
        negativeReviewMapper.updateById(review);

        log.info("差评跟进邮件已创建：reviewId={}, taskId={}", reviewId, task.getId());
        return task;
    }

    // ==================== RMA 退货 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Rma createRma(Rma rma) {
        if (rma.getShopId() == null || rma.getAmazonOrderId() == null) {
            throw new AttrIsNullException("店铺ID和订单号不能为空");
        }
        rma.setRmaNo("RMA" + System.currentTimeMillis());
        if (rma.getStatus() == null) rma.setStatus("PENDING");
        if (rma.getReturnType() == null) rma.setReturnType("RETURN");
        if (rma.getLabelCost() == null) rma.setLabelCost(java.math.BigDecimal.ZERO);
        if (rma.getRefundAmount() == null) rma.setRefundAmount(java.math.BigDecimal.ZERO);
        rmaMapper.insert(rma);
        log.info("RMA 退货申请已创建：rmaNo={}, orderId={}", rma.getRmaNo(), rma.getAmazonOrderId());
        return rma;
    }

    @Override
    public List<Rma> listRmas(Long shopId, String status) {
        LambdaQueryWrapper<Rma> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Rma::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Rma::getStatus, status);
        }
        wrapper.orderByDesc(Rma::getId);
        return rmaMapper.selectList(wrapper);
    }

    @Override
    public Rma updateRmaStatus(Long rmaId, String status) {
        Rma rma = rmaMapper.selectById(rmaId);
        if (rma == null) {
            throw new AttrIsNullException("RMA不存在：id=" + rmaId);
        }
        rma.setStatus(status);
        rmaMapper.updateById(rma);
        return rma;
    }

    @Override
    public Rma getRma(Long rmaId) {
        Rma rma = rmaMapper.selectById(rmaId);
        if (rma == null) {
            throw new AttrIsNullException("RMA不存在：id=" + rmaId);
        }
        return rma;
    }

    // ==================== 工具方法 ====================

    /**
     * 渲染邮件模板变量。
     * 支持：{buyer_name} {order_id} {asin} {tracking_no} {review_link}
     */
    private String renderTemplate(String template, String orderId, String asin, String buyerName, String trackingNo) {
        if (template == null) return "";
        String result = template;
        result = result.replace("{buyer_name}", buyerName != null ? buyerName : "Customer");
        result = result.replace("{order_id}", orderId != null ? orderId : "");
        result = result.replace("{asin}", asin != null ? asin : "");
        result = result.replace("{tracking_no}", trackingNo != null ? trackingNo : "");
        result = result.replace("{review_link}", "https://www.amazon.com/review/create-review");
        return result;
    }
}
