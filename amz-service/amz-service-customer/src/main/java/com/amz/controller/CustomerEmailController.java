package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.EmailTemplate;
import com.amz.model.EmailTask;
import com.amz.model.NegativeReview;
import com.amz.model.Rma;
import com.amz.result.Result;
import com.amz.service.CustomerEmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 客服管理升级 REST 端点。
 * <p>
 * 覆盖：邮件模板 / 自动化邮件 / 差评监控 / RMA 退货
 */
@RestController
@RequestMapping("/customer/email")
public class CustomerEmailController {

    @Autowired
    private CustomerEmailService customerEmailService;

    // ==================== 邮件模板 ====================

    /** 创建邮件模板 */
    @ShopScoped
    @PostMapping("/template")
    public Result<EmailTemplate> createTemplate(@RequestBody EmailTemplate template) {
        return Result.success(customerEmailService.createTemplate(template));
    }

    /** 更新模板 */
    @ShopScoped
    @PutMapping("/template/{id}")
    public Result<EmailTemplate> updateTemplate(@PathVariable Long id, @RequestBody EmailTemplate template) {
        template.setId(id);
        return Result.success(customerEmailService.updateTemplate(template));
    }

    /** 查询模板列表 */
    @ShopScoped
    @GetMapping("/template/list/{shopId}")
    public Result<List<EmailTemplate>> listTemplates(@PathVariable Long shopId,
                                                      @RequestParam(required = false) String templateType) {
        return Result.success(customerEmailService.listTemplates(shopId, templateType));
    }

    /** 启用/禁用模板 */
    @ShopScoped
    @PostMapping("/template/{id}/toggle")
    public Result<Boolean> toggleTemplate(@PathVariable Long id, @RequestParam boolean enabled) {
        return Result.success(customerEmailService.toggleTemplate(id, enabled));
    }

    // ==================== 自动化邮件 ====================

    /** 触发事件邮件（订单确认/发货/索评等） */
    @ShopScoped
    @PostMapping("/trigger/{shopId}")
    public Result<EmailTask> triggerEmail(@PathVariable Long shopId,
                                          @RequestParam String eventType,
                                          @RequestParam String orderId,
                                          @RequestParam(required = false) String asin,
                                          @RequestParam(required = false) String buyerEmail,
                                          @RequestParam(required = false) String buyerName,
                                          @RequestParam(required = false) String trackingNo) {
        return Result.success(customerEmailService.triggerEmailByEvent(shopId, eventType, orderId, asin, buyerEmail, buyerName, trackingNo));
    }

    /** 执行待发送邮件 */
    @ShopScoped
    @PostMapping("/process/{shopId}")
    public Result<Map<String, Object>> processPendingEmails(@PathVariable Long shopId) {
        return Result.success(customerEmailService.processPendingEmails(shopId));
    }

    /** 查询邮件任务列表 */
    @ShopScoped
    @GetMapping("/task/list/{shopId}")
    public Result<List<EmailTask>> listEmailTasks(@PathVariable Long shopId,
                                                   @RequestParam(required = false) String status) {
        return Result.success(customerEmailService.listEmailTasks(shopId, status));
    }

    /** 手动创建邮件任务 */
    @ShopScoped
    @PostMapping("/task/manual")
    public Result<EmailTask> createManualTask(@RequestBody EmailTask task) {
        return Result.success(customerEmailService.createEmailTaskManually(task));
    }

    // ==================== 差评监控 ====================

    /** 保存差评数据 */
    @ShopScoped
    @PostMapping("/negative-review")
    public Result<NegativeReview> saveNegativeReview(@RequestBody NegativeReview review) {
        return Result.success(customerEmailService.saveNegativeReview(review));
    }

    /** 查询差评列表 */
    @ShopScoped
    @GetMapping("/negative-review/list/{shopId}")
    public Result<List<NegativeReview>> listNegativeReviews(@PathVariable Long shopId,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(required = false) Integer minRating) {
        return Result.success(customerEmailService.listNegativeReviews(shopId, status, minRating));
    }

    /** 差评-订单匹配 */
    @ShopScoped
    @PostMapping("/negative-review/{reviewId}/match")
    public Result<Map<String, Object>> matchReviewToOrder(@PathVariable Long reviewId) {
        return Result.success(customerEmailService.matchNegativeReviewToOrder(reviewId));
    }

    /** 差评自动跟进（创建跟进邮件） */
    @ShopScoped
    @PostMapping("/negative-review/{reviewId}/followup")
    public Result<EmailTask> followUpNegativeReview(@PathVariable Long reviewId) {
        return Result.success(customerEmailService.followUpNegativeReview(reviewId));
    }

    // ==================== RMA 退货 ====================

    /** 创建 RMA 退货申请 */
    @ShopScoped
    @PostMapping("/rma")
    public Result<Rma> createRma(@RequestBody Rma rma) {
        return Result.success(customerEmailService.createRma(rma));
    }

    /** 查询 RMA 列表 */
    @ShopScoped
    @GetMapping("/rma/list/{shopId}")
    public Result<List<Rma>> listRmas(@PathVariable Long shopId,
                                      @RequestParam(required = false) String status) {
        return Result.success(customerEmailService.listRmas(shopId, status));
    }

    /** 更新 RMA 状态 */
    @ShopScoped
    @PostMapping("/rma/{rmaId}/status")
    public Result<Rma> updateRmaStatus(@PathVariable Long rmaId, @RequestParam String status) {
        return Result.success(customerEmailService.updateRmaStatus(rmaId, status));
    }

    /** 获取 RMA 详情 */
    @ShopScoped
    @GetMapping("/rma/{rmaId}")
    public Result<Rma> getRma(@PathVariable Long rmaId) {
        return Result.success(customerEmailService.getRma(rmaId));
    }
}
