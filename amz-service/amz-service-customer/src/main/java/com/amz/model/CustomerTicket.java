package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 客服工单实体（多店铺统一消息中心）。
 * <p>
 * 消息来源：Amazon Buyer-Seller Messaging、Review 评论、退货申请、站内信等。
 * AI 自动分类后填充 category + priority + sentiment，进入对应处理队列。
 */
@Data
@TableName("amz_customer_ticket")
public class CustomerTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺 ID */
    private Long shopId;

    /** Amazon 订单号 */
    private String amazonOrderId;

    /** 买家 ID */
    private String buyerId;

    /** 买家昵称 */
    private String buyerName;

    /** 消息渠道：MESSAGE / REVIEW / RETURN / A_TO_Z */
    private String channel;

    /** 买家原始消息内容 */
    private String content;

    /** AI 分类：SHIPPING(物流) / PRODUCT_QUALITY(产品质量) / RETURN_REFUND(退货退款) / INVOICE(发票) / OTHER */
    private String category;

    /** 优先级：URGENT / HIGH / NORMAL / LOW（AI 根据情绪+关键词判定） */
    private String priority;

    /** 情感倾向：POSITIVE / NEUTRAL / NEGATIVE / ANGRY */
    private String sentiment;

    /** 状态：PENDING / ASSIGNED / REPLIED / RESOLVED / ESCALATED */
    private String status;

    /** 客服回复内容 */
    private String reply;
}
