package com.amz.service;

import com.amz.model.CustomerTicket;
import com.amz.model.ReviewSolicitation;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

/**
 * 客服工单服务接口。
 */
public interface CustomerService {

    /**
     * 接收买家消息，AI 自动分类后入库。
     */
    CustomerTicket receiveMessage(CustomerTicket ticket);

    /**
     * 客服回复工单。
     */
    CustomerTicket replyTicket(Long ticketId, String reply);

    /**
     * 查询店铺工单列表（可按状态/分类筛选）。
     */
    PageResult<CustomerTicket> listTickets(Long shopId, String status, String category, PageRequest page);

    /**
     * 批量发送索评请求（仅合规订单：已签收 + 未留评 + 30 天内 + 未发过）。
     *
     * @return 实际发送的索评数
     */
    int sendReviewSolicitations(Long shopId);

    /**
     * 查询店铺索评记录。
     */
    PageResult<ReviewSolicitation> listSolicitations(Long shopId, PageRequest page);
}
