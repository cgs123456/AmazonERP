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
import org.springframework.core.env.Environment;
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

    @Autowired
    private Environment environment;

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
        // 生产实现需通过 SP-API 拉取订单列表 + 调用 Request a Review 接口。
        // 非 mock 环境下诚实失败：伪造 SENT 记录会让运营误以为索评已发出（合规风险）
        if (!isMockProfile()) {
            throw new IllegalStateException(
                    "索评依赖 SP-API 'Request a Review' 接口，尚未接入真实发送通道"
                            + "（本地演示请启用 mock profile），shopId=" + shopId);
        }

        LambdaQueryWrapper<ReviewSolicitation> sentWrapper = new LambdaQueryWrapper<>();
        sentWrapper.eq(ReviewSolicitation::getShopId, shopId);
        long sentCount = solicitationMapper.selectCount(sentWrapper);
        log.warn("[MOCK] 索评助手：shopId={} 已发送 {} 条，本次为模拟数据新增 5 条（非真实发送）", shopId, sentCount);

        // 模拟：为 5 个未索评订单创建请求记录
        int created = 0;
        for (int i = 1; i <= 5; i++) {
            ReviewSolicitation r = new ReviewSolicitation();
            r.setShopId(shopId);
            r.setAmazonOrderId("SIMULATED-" + System.currentTimeMillis() + "-" + i);
            r.setAsin("B0" + (1000000 + i));
            r.setChannel("OFFICIAL_BUTTON");
            r.setStatus("SENT");
            solicitationMapper.insert(r);
            created++;
        }
        return created;
    }

    /** 是否运行在 mock profile（模拟数据仅在 mock 环境允许生成）。 */
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

    @Override
    public List<ReviewSolicitation> listSolicitations(Long shopId) {
        LambdaQueryWrapper<ReviewSolicitation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewSolicitation::getShopId, shopId).orderByDesc(ReviewSolicitation::getId);
        return solicitationMapper.selectList(wrapper);
    }
}
