package com.amz.service;

import com.amz.model.EmailTemplate;
import com.amz.model.EmailTask;
import com.amz.model.NegativeReview;
import com.amz.model.Rma;

import java.util.List;
import java.util.Map;

/**
 * 客服管理升级服务接口。
 * <p>
 * 覆盖：邮件模板/自动化邮件/差评监控/RMA 退货/客服KPI
 */
public interface CustomerEmailService {

    // ===== 邮件模板 =====

    /** 创建邮件模板 */
    EmailTemplate createTemplate(EmailTemplate template);

    /** 更新模板 */
    EmailTemplate updateTemplate(EmailTemplate template);

    /** 查询模板列表 */
    List<EmailTemplate> listTemplates(Long shopId, String templateType);

    /** 启用/禁用模板 */
    boolean toggleTemplate(Long templateId, boolean enabled);

    // ===== 自动化邮件 =====

    /** 根据订单事件触发自动化邮件（生成 EmailTask） */
    EmailTask triggerEmailByEvent(Long shopId, String eventType, String orderId, String asin,
                                  String buyerEmail, String buyerName, String trackingNo);

    /** 执行待发送的邮件任务（定时任务调用） */
    Map<String, Object> processPendingEmails(Long shopId);

    /** 查询邮件任务列表 */
    List<EmailTask> listEmailTasks(Long shopId, String status);

    /** 手动创建邮件任务 */
    EmailTask createEmailTaskManually(EmailTask task);

    // ===== 差评监控 =====

    /** 保存差评数据（从 Amazon 前台抓取或 SP-API 拉取） */
    NegativeReview saveNegativeReview(NegativeReview review);

    /** 查询差评列表 */
    List<NegativeReview> listNegativeReviews(Long shopId, String status, Integer minRating);

    /** 差评-订单匹配：尝试通过 ASIN + 留评时间匹配买家订单 */
    Map<String, Object> matchNegativeReviewToOrder(Long reviewId);

    /** 差评自动跟进：为已匹配订单的差评创建跟进邮件任务 */
    EmailTask followUpNegativeReview(Long reviewId);

    // ===== RMA 退货 =====

    /** 创建 RMA 退货申请 */
    Rma createRma(Rma rma);

    /** 查询 RMA 列表 */
    List<Rma> listRmas(Long shopId, String status);

    /** 更新 RMA 状态 */
    Rma updateRmaStatus(Long rmaId, String status);

    /** 获取 RMA 详情 */
    Rma getRma(Long rmaId);
}
