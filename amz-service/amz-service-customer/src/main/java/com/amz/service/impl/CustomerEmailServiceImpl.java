package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.EmailTemplateMapper;
import com.amz.mapper.EmailTaskMapper;
import com.amz.mapper.NegativeReviewMapper;
import com.amz.model.EmailTask;
import com.amz.model.EmailTemplate;
import com.amz.model.NegativeReview;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.CustomerEmailService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 单次处理上限：极端积压时分批消化，避免一次全量载入 OOM。 */
    private static final int PENDING_BATCH_LIMIT = 1000;

    @Autowired
    private EmailTemplateMapper emailTemplateMapper;

    @Autowired
    private EmailTaskMapper emailTaskMapper;

    @Autowired
    private NegativeReviewMapper negativeReviewMapper;

    @Autowired
    private Environment environment;

    /** 共享辅助（归属校验 + 模板渲染），见 {@link CustomerEmailSupport}。 */
    @Autowired
    private CustomerEmailSupport support;

    /**
     * SMTP 发送器。
     * <p>
     * {@code required = false}：部分测试切片不装配邮件 Bean。为空时
     * {@code processPendingEmails} 走「通道不可用」分支并明确标记失败——
     * 与 host 未配置同一条路径，不会伪造 SENT。
     */
    @Autowired(required = false)
    private SmtpEmailSender smtpEmailSender;

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
        // 越权防护：Body-only 创建入口无 shopId 参数可被切面拦截，显式校验目标店铺归属
        if (!UserContext.isShopAllowed(template.getShopId())) {
            throw new IllegalStateException("无权操作该店铺的邮件模板");
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
        // 越权防护：不信任请求体自带的 shopId，按既有记录校验归属并覆盖写回，
        // 防止把模板搬到其他店铺（updateById 全字段更新会连带写入请求体 shopId）
        EmailTemplate existed = support.mustGetAuthorizedTemplate(template.getId());
        template.setShopId(existed.getShopId());
        emailTemplateMapper.updateById(template);
        return template;
    }

    @Override
    public PageResult<EmailTemplate> listTemplates(Long shopId, String templateType, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<EmailTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmailTemplate::getShopId, shopId);
        if (templateType != null && !templateType.isBlank()) {
            wrapper.eq(EmailTemplate::getTemplateType, templateType);
        }
        if (req.hasCursor()) {
            wrapper.lt(EmailTemplate::getId, req.cursorId());
        }
        wrapper.orderByDesc(EmailTemplate::getId)
               .last("LIMIT " + req.probeSize());
        List<EmailTemplate> rows = emailTemplateMapper.selectList(wrapper);
        PageResult<EmailTemplate> result = PageResult.of(rows, req.size(),
                t -> PageRequest.encodeCursor(t.getId()));
        if (result.truncated()) {
            log.warn("邮件模板列表被分页截断：shopId={} type={} size={}", shopId, templateType, req.size());
        }
        return result;
    }

    @Override
    public boolean toggleTemplate(Long templateId, boolean enabled) {
        EmailTemplate template = support.mustGetAuthorizedTemplate(templateId);
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
               .eq(EmailTemplate::getEnabled, 1)
               .orderByAsc(EmailTemplate::getId)
               .last("LIMIT 1");
        List<EmailTemplate> templates = emailTemplateMapper.selectList(wrapper);

        if (templates.isEmpty()) {
            log.info("无匹配的邮件模板：shopId={}, event={}", shopId, eventType);
            return null;
        }

        EmailTemplate template = templates.get(0);

        // 渲染模板变量
        String subject = support.renderTemplate(template.getSubject(), orderId, asin, buyerName, trackingNo);
        String body = support.renderTemplate(template.getBody(), orderId, asin, buyerName, trackingNo);

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
               .le(EmailTask::getScheduledTime, LocalDateTime.now())
               // 单次上限：极端积压时分批消化，避免一次全量载入 OOM；剩余下次调度继续。
               // 多取 1 条只为判断「是否还有积压」：命中上限却不说，
               // 调用方会把「本批做完」当成「积压已清空」（P1-01）。
               .orderByAsc(EmailTask::getId)
               .last("LIMIT " + (PENDING_BATCH_LIMIT + 1));
        List<EmailTask> pendingTasks = emailTaskMapper.selectList(wrapper);
        boolean hasMorePending = pendingTasks.size() > PENDING_BATCH_LIMIT;
        if (hasMorePending) {
            pendingTasks = new ArrayList<>(pendingTasks.subList(0, PENDING_BATCH_LIMIT));
            log.warn("待发邮件仍有积压：shopId={} 本批只处理 {} 条，需再次调度", shopId, PENDING_BATCH_LIMIT);
        }

        int sent = 0;
        int failed = 0;
        List<Map<String, Object>> failures = new ArrayList<>();

        for (EmailTask task : pendingTasks) {
            try {
                if (isMockProfile()) {
                    // mock 档：不真的发信，日志明确标注，避免演示环境误以为已发出
                    log.warn("[MOCK] 模拟邮件发送成功：taskId={}, orderId={}（非真实发送）",
                            task.getId(), task.getAmazonOrderId());
                    task.setStatus("SENT");
                    task.setSentTime(LocalDateTime.now());
                    emailTaskMapper.updateById(task);
                    sent++;
                    continue;
                }
                // 非 mock：走 SMTP。通道未配置时明确失败，绝不伪造 SENT。
                // 这条判定放在 here 而不是更早，是为了让每个任务都留下 failureReason——
                // 只在开头发一条全局日志的话，运营在任务列表里看到的仍是一排无原因的 FAILED。
                if (smtpEmailSender == null || !smtpEmailSender.isAvailable()) {
                    String reason = smtpEmailSender == null
                            ? "SMTP 发送器未装配"
                            : smtpEmailSender.unavailableReason();
                    markFailed(task, reason, failures);
                    failed++;
                    continue;
                }
                smtpEmailSender.send(task.getBuyerEmail(), task.getSubject(), task.getBody());
                task.setStatus("SENT");
                task.setSentTime(LocalDateTime.now());
                task.setFailureReason(null);
                emailTaskMapper.updateById(task);
                sent++;
            } catch (Exception e) {
                markFailed(task, e.getMessage(), failures);
                failed++;
                log.error("邮件发送失败：taskId={}", task.getId(), e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        // totalPending 沿用旧键名，但语义收紧为「本批次处理条数」：
        // 旧值同样只是批次大小，却容易被读成「全店待发总量」。
        result.put("totalPending", pendingTasks.size());
        result.put("batchSize", pendingTasks.size());
        result.put("batchLimit", PENDING_BATCH_LIMIT);
        result.put("hasMorePending", hasMorePending);
        result.put("sent", sent);
        result.put("failed", failed);
        result.put("failures", failures);
        return result;
    }

    /**
     * 把任务标为 FAILED 并记入失败清单。
     * <p>
     * 两个失败分支（通道不可用 / 发送异常）必须写同一组字段：运营看到的是
     * {@code failure_reason}，任何一条分支漏写都会让任务列表出现「FAILED 但没原因」。
     */
    private void markFailed(EmailTask task, String reason, List<Map<String, Object>> failures) {
        task.setStatus("FAILED");
        task.setFailureReason(reason);
        emailTaskMapper.updateById(task);

        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("taskId", task.getId());
        failure.put("orderId", task.getAmazonOrderId());
        failure.put("error", reason);
        failures.add(failure);
    }

    @Override
    public PageResult<EmailTask> listEmailTasks(Long shopId, String status, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<EmailTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmailTask::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(EmailTask::getStatus, status);
        }
        if (req.hasCursor()) {
            wrapper.lt(EmailTask::getId, req.cursorId());
        }
        // 此端点此前完全没有上限（P1-02 无界列表），一并纳入统一分页
        wrapper.orderByDesc(EmailTask::getId)
               .last("LIMIT " + req.probeSize());
        List<EmailTask> rows = emailTaskMapper.selectList(wrapper);
        PageResult<EmailTask> result = PageResult.of(rows, req.size(),
                t -> PageRequest.encodeCursor(t.getId()));
        if (result.truncated()) {
            log.warn("邮件任务列表被分页截断：shopId={} status={} size={}", shopId, status, req.size());
        }
        return result;
    }

    @Override
    public EmailTask createEmailTaskManually(EmailTask task) {
        if (task.getShopId() == null || task.getAmazonOrderId() == null) {
            throw new AttrIsNullException("店铺ID和订单号不能为空");
        }
        if (!UserContext.isShopAllowed(task.getShopId())) {
            throw new IllegalStateException("无权操作该店铺的邮件任务");
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
        if (!UserContext.isShopAllowed(review.getShopId())) {
            throw new IllegalStateException("无权操作该店铺的差评");
        }
        review.setStatus("DETECTED");
        if (review.getVerifiedPurchase() == null) review.setVerifiedPurchase(0);
        negativeReviewMapper.insert(review);
        log.info("差评已记录：shopId={}, asin={}, rating={}", review.getShopId(), review.getAsin(), review.getReviewRating());
        return review;
    }

    @Override
    public PageResult<NegativeReview> listNegativeReviews(Long shopId, String status, Integer minRating,
                                                           PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<NegativeReview> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NegativeReview::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(NegativeReview::getStatus, status);
        }
        if (minRating != null) {
            wrapper.le(NegativeReview::getReviewRating, minRating);
        }
        if (req.hasCursor()) {
            wrapper.lt(NegativeReview::getId, req.cursorId());
        }
        wrapper.orderByDesc(NegativeReview::getId)
               .last("LIMIT " + req.probeSize());
        List<NegativeReview> rows = negativeReviewMapper.selectList(wrapper);
        PageResult<NegativeReview> result = PageResult.of(rows, req.size(),
                t -> PageRequest.encodeCursor(t.getId()));
        if (result.truncated()) {
            log.warn("差评列表被分页截断：shopId={} status={} minRating={} size={}",
                    shopId, status, minRating, req.size());
        }
        return result;
    }

    @Override
    public Map<String, Object> matchNegativeReviewToOrder(Long reviewId) {
        NegativeReview review = support.mustGetAuthorizedReview(reviewId);

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
        NegativeReview review = support.mustGetAuthorizedReview(reviewId);
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
        String subject = support.renderTemplate(template.getSubject(), orderId, review.getAsin(), review.getReviewerName(), null);
        String body = support.renderTemplate(template.getBody(), orderId, review.getAsin(), review.getReviewerName(), null);

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

    @Override
    public NegativeReview resolveNegativeReview(Long reviewId) {
        NegativeReview review = support.mustGetAuthorizedReview(reviewId);
        String status = review.getStatus();
        if ("RESOLVED".equals(status)) {
            // 结案幂等：重复点返回当前行
            return review;
        }
        if (!"DETECTED".equals(status) && !"MATCHED".equals(status) && !"CONTACTED".equals(status)) {
            throw new IllegalStateException("差评当前状态为「" + status + "」，不可结案");
        }
        review.setStatus("RESOLVED");
        negativeReviewMapper.updateById(review);
        log.info("差评结案 reviewId={} 原状态={}", reviewId, status);
        return review;
    }
}
