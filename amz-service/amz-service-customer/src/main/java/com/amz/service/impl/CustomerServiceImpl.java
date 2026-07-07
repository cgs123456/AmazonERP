package com.amz.service.impl;

import com.amz.classifier.TicketClassifier;
import com.amz.mapper.CustomerTicketMapper;
import com.amz.mapper.ReviewSolicitationMapper;
import com.amz.model.CustomerTicket;
import com.amz.model.ReviewSolicitation;
import com.amz.service.CustomerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 客服工单服务实现。
 */
@Slf4j
@Service
public class CustomerServiceImpl implements CustomerService {

    @Autowired
    private CustomerTicketMapper ticketMapper;

    @Autowired
    private ReviewSolicitationMapper solicitationMapper;

    @Autowired
    private TicketClassifier classifier;

    @Override
    public CustomerTicket receiveMessage(CustomerTicket ticket) {
        // AI 自动分类
        TicketClassifier.Classification c = classifier.classify(ticket.getContent());
        ticket.setCategory(c.getCategory());
        ticket.setPriority(c.getPriority());
        ticket.setSentiment(c.getSentiment());
        ticket.setStatus("PENDING");
        ticketMapper.insert(ticket);
        log.info("工单入库：shopId={} category={} priority={}", ticket.getShopId(), c.getCategory(), c.getPriority());
        return ticket;
    }

    @Override
    public CustomerTicket replyTicket(Long ticketId, String reply) {
        CustomerTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new IllegalArgumentException("工单不存在：id=" + ticketId);
        }
        ticket.setReply(reply);
        ticket.setStatus("REPLIED");
        ticketMapper.updateById(ticket);
        return ticket;
    }

    @Override
    public List<CustomerTicket> listTickets(Long shopId, String status, String category) {
        LambdaQueryWrapper<CustomerTicket> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomerTicket::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(CustomerTicket::getStatus, status);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(CustomerTicket::getCategory, category);
        }
        wrapper.orderByDesc(CustomerTicket::getId);
        return ticketMapper.selectList(wrapper);
    }

    @Override
    public int sendReviewSolicitations(Long shopId) {
        // 合规筛选：已签收 + 30 天内 + 未发过索评
        // 生产环境应通过 SP-API 拉取订单列表 + 调用 Request a Review 接口
        LambdaQueryWrapper<ReviewSolicitation> sentWrapper = new LambdaQueryWrapper<>();
        sentWrapper.eq(ReviewSolicitation::getShopId, shopId);
        long sentCount = solicitationMapper.selectCount(sentWrapper);
        log.info("索评助手：shopId={} 已发送 {} 条，模拟本次新增 5 条", shopId, sentCount);

        // 模拟：为 5 个未索评订单创建请求记录
        int created = 0;
        for (int i = 1; i <= 5; i++) {
            ReviewSolicitation r = new ReviewSolicitation();
            r.setShopId(shopId);
            r.setAmazonOrderId("AMZ-" + System.currentTimeMillis() + "-" + i);
            r.setAsin("B0" + (1000000 + i));
            r.setChannel("OFFICIAL_BUTTON");
            r.setStatus("SENT");
            solicitationMapper.insert(r);
            created++;
        }
        return created;
    }

    @Override
    public List<ReviewSolicitation> listSolicitations(Long shopId) {
        LambdaQueryWrapper<ReviewSolicitation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewSolicitation::getShopId, shopId).orderByDesc(ReviewSolicitation::getId);
        return solicitationMapper.selectList(wrapper);
    }
}
