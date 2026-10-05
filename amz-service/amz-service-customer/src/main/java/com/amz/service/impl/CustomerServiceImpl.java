package com.amz.service.impl;

import com.amz.classifier.TicketClassifier;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.CustomerTicketMapper;
import com.amz.mapper.ReviewSolicitationMapper;
import com.amz.model.CustomerTicket;
import com.amz.model.ReviewSolicitation;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.CustomerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 客服工单服务实现。
 * <p>
 * 工单的 shopId 在请求体/行上而不在参数里，{@code @ShopScoped} 切面拦不到，
 * 归属校验必须在 service 层按行做（与 MultiplatformServiceImpl.requireShopOnRow 同一口径）：
 * 回复/关闭他人店铺的工单、向任意店铺灌工单，都是跨租户越权。
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

    /** 行归属校验：工单行上的 shopId 必须命中当前用户授权店铺，否则点名拒绝。 */
    private void requireShopOnRow(Long shopId) {
        if (!UserContext.isShopAllowedStrict(shopId)) {
            log.warn("客服越权拦截：userId={}, role={}, 工单ShopId={}",
                    UserContext.getUserId(), UserContext.getRole(), shopId);
            throw new CodeErrorException("客服工单不存在或无权访问");
        }
    }

    @Override
    public CustomerTicket receiveMessage(CustomerTicket ticket) {
        // 请求体里的 shopId 切面覆盖不到，归属只能在这里按行判定
        requireShopOnRow(ticket.getShopId());
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
        // 行上的 shopId 不在参数里，切面拦不到，按行校验归属
        requireShopOnRow(ticket.getShopId());
        ticket.setReply(reply);
        ticket.setStatus("REPLIED");
        ticketMapper.updateById(ticket);
        return ticket;
    }

    @Override
    public PageResult<CustomerTicket> listTickets(Long shopId, String status, String category, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<CustomerTicket> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomerTicket::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(CustomerTicket::getStatus, status);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(CustomerTicket::getCategory, category);
        }
        if (req.hasCursor()) {
            wrapper.lt(CustomerTicket::getId, req.cursorId());
        }
        wrapper.orderByDesc(CustomerTicket::getId)
               .last("LIMIT " + req.probeSize());
        List<CustomerTicket> rows = ticketMapper.selectList(wrapper);
        PageResult<CustomerTicket> result = PageResult.of(rows, req.size(),
                t -> PageRequest.encodeCursor(t.getId()));
        if (result.truncated()) {
            log.warn("工单列表被分页截断：shopId={} status={} category={} size={}",
                    shopId, status, category, req.size());
        }
        return result;
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
    public PageResult<ReviewSolicitation> listSolicitations(Long shopId, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<ReviewSolicitation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReviewSolicitation::getShopId, shopId);
        if (req.hasCursor()) {
            wrapper.lt(ReviewSolicitation::getId, req.cursorId());
        }
        wrapper.orderByDesc(ReviewSolicitation::getId)
               .last("LIMIT " + req.probeSize());
        List<ReviewSolicitation> rows = solicitationMapper.selectList(wrapper);
        PageResult<ReviewSolicitation> result = PageResult.of(rows, req.size(),
                t -> PageRequest.encodeCursor(t.getId()));
        if (result.truncated()) {
            log.warn("索评列表被分页截断：shopId={} size={}", shopId, req.size());
        }
        return result;
    }
}
