package com.amz.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 索评请求实体（索评助手）。
 * <p>
 * 合规索评（遵循 Amazon ToS）：
 * <ul>
 *   <li>仅对已签收且未留评的订单发送</li>
 *   <li>每个订单仅发送一次</li>
 *   <li>内容不得诱导好评/奖励评价</li>
 *   <li>需通过 Amazon "Request a Review" 按钮官方接口</li>
 * </ul>
 */
@Data
@TableName("amz_review_solicitation")
public class ReviewSolicitation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属店铺 ID */
    private Long shopId;

    /** Amazon 订单号 */
    private String amazonOrderId;

    /** 商品 ASIN */
    private String asin;

    /** 状态：PENDING / SENT / FAILED / OPTED_OUT（买家拒收） */
    private String status;

    /** 发送渠道：OFFICIAL_BUTTON（官方按钮）/ EMAIL */
    private String channel;

    /** 失败原因（FAILED 时填） */
    private String failureReason;
}
