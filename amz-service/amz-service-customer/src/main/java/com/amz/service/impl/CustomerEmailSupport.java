package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.EmailTemplateMapper;
import com.amz.mapper.NegativeReviewMapper;
import com.amz.mapper.RmaMapper;
import com.amz.model.EmailTemplate;
import com.amz.model.NegativeReview;
import com.amz.model.Rma;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 客服邮件域的共享辅助：多租户越权校验 + 模板渲染。
 * <p>
 * <b>为什么从 {@link CustomerEmailServiceImpl} 抽出来</b>：那个类已到 575 行，
 * 超过 CLAUDE.md 的 500 行上限。抽出的是两类<b>与业务流程无关</b>的东西：
 * <ol>
 *   <li><b>记录归属校验</b>。模板/差评/RMA 的若干端点只带记录 ID、没有 shopId 参数，
 *       {@code @ShopScoped} 切面按参数名解析不到，必须在服务层逐条校验。
 *       这是本域所有写路径的公共前置条件，散落在主类里既占行数又容易被新方法漏用。</li>
 *   <li><b>模板变量渲染</b>。纯函数，无状态。</li>
 * </ol>
 * <p>
 * <b>刻意不抽的东西</b>：邮件任务的状态机（PENDING→SENT/FAILED）、批量分页、
 * SMTP 调用顺序。它们耦合了「发信是否真的成功」这一核心正确性，留在主类里
 * 与调用点相邻更好读。抽辅助方法不是把类切碎，而是把无状态的部分移出去。
 * <p>
 * <b>越权与不存在共用同一句文案</b>：分开写会让端点变成「这条记录是否存在」的探针。
 * 与 {@code PerformanceController.detail}、{@code ProcurementServiceImpl#mustGetAuthorized}
 * 同一口径。
 */
@Component
class CustomerEmailSupport {

    private final EmailTemplateMapper emailTemplateMapper;
    private final NegativeReviewMapper negativeReviewMapper;
    private final RmaMapper rmaMapper;

    @Autowired
    CustomerEmailSupport(EmailTemplateMapper emailTemplateMapper,
                         NegativeReviewMapper negativeReviewMapper,
                         RmaMapper rmaMapper) {
        this.emailTemplateMapper = emailTemplateMapper;
        this.negativeReviewMapper = negativeReviewMapper;
        this.rmaMapper = rmaMapper;
    }

    /**
     * 加载邮件模板并做多租户越权校验（与 ProcurementServiceImpl#mustGetAuthorized 同模式）。
     * <p>
     * 模板/启停端点仅携带记录 ID、无 shopId 参数，ShopScoped 切面无法按参数名拦截，
     * 故在服务层显式校验记录所属店铺是否在当前用户授权范围内。
     */
    EmailTemplate mustGetAuthorizedTemplate(Long templateId) {
        EmailTemplate template = emailTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new AttrIsNullException("模板不存在：id=" + templateId);
        }
        if (!UserContext.isShopAllowed(template.getShopId())) {
            throw new IllegalStateException("无权操作该店铺的邮件模板：id=" + templateId);
        }
        return template;
    }

    /** 加载 RMA 并做多租户越权校验（同上）。 */
    Rma mustGetAuthorizedRma(Long rmaId) {
        Rma rma = rmaMapper.selectById(rmaId);
        if (rma == null) {
            throw new AttrIsNullException("RMA不存在：id=" + rmaId);
        }
        if (!UserContext.isShopAllowed(rma.getShopId())) {
            throw new IllegalStateException("无权操作该店铺的 RMA：id=" + rmaId);
        }
        return rma;
    }

    /**
     * 加载差评并做多租户越权校验（同上）。
     * <p>
     * match/followup 端点仅携带 reviewId、无 shopId 参数，切面无法拦截，
     * 故在此显式校验（防跨租户读写他店差评、冒建他店邮件任务）。
     */
    NegativeReview mustGetAuthorizedReview(Long reviewId) {
        NegativeReview review = negativeReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new AttrIsNullException("差评记录不存在：id=" + reviewId);
        }
        if (!UserContext.isShopAllowed(review.getShopId())) {
            throw new IllegalStateException("无权操作该店铺的差评：id=" + reviewId);
        }
        return review;
    }

    /**
     * 渲染邮件模板变量。
     * <p>
     * 支持：{@code {buyer_name}} / {@code {order_id}} / {@code {asin}} /
     * {@code {tracking_no}} / {@code {review_link}}。
     * <p>
     * 未知占位符原样保留——静默替换成空串会让运营看不出模板里写错了变量名。
     */
    String renderTemplate(String template, String orderId, String asin,
                          String buyerName, String trackingNo) {
        if (template == null) {
            return "";
        }
        String result = template;
        result = result.replace("{buyer_name}", buyerName != null ? buyerName : "Customer");
        result = result.replace("{order_id}", orderId != null ? orderId : "");
        result = result.replace("{asin}", asin != null ? asin : "");
        result = result.replace("{tracking_no}", trackingNo != null ? trackingNo : "");
        result = result.replace("{review_link}", "https://www.amazon.com/review/create-review");
        return result;
    }
}
