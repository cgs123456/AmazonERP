package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.mapper.RmaMapper;
import com.amz.model.Rma;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.RmaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * RMA 退货单服务实现。
 * <p>
 * 从 {@code CustomerEmailServiceImpl} 拆出（原类 515 行超限）。行为逐字保留，
 * 包括：创建时显式校验店铺归属、默认值填充、列表分页截断告警、详情走越权校验。
 * <p>
 * <b>为什么创建要显式校验</b>：{@code createRma} 的 shopId 只在请求体里，
 * {@code @ShopScoped} 切面按参数名解析不到时按兼容放行，若 service 层再不校验，
 * 任何登录用户都能往任意店铺建退货单。
 */
@Slf4j
@Service
public class RmaServiceImpl implements RmaService {

    @Autowired
    private RmaMapper rmaMapper;

    @Autowired
    private CustomerEmailSupport support;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Rma createRma(Rma rma) {
        if (rma.getShopId() == null || rma.getAmazonOrderId() == null) {
            throw new AttrIsNullException("店铺ID和订单号不能为空");
        }
        if (!UserContext.isShopAllowed(rma.getShopId())) {
            throw new IllegalStateException("无权操作该店铺的 RMA");
        }
        // 单号用毫秒时间戳：RMA 是低频业务，同毫秒碰撞概率可忽略；
        // 若将来量级上来，改用 BizNoGenerator（带随机后缀）即可，字段长度足够。
        rma.setRmaNo("RMA" + System.currentTimeMillis());
        if (rma.getStatus() == null) rma.setStatus("PENDING");
        if (rma.getReturnType() == null) rma.setReturnType("RETURN");
        if (rma.getLabelCost() == null) rma.setLabelCost(BigDecimal.ZERO);
        if (rma.getRefundAmount() == null) rma.setRefundAmount(BigDecimal.ZERO);
        rmaMapper.insert(rma);
        log.info("RMA 退货申请已创建：rmaNo={}, orderId={}", rma.getRmaNo(), rma.getAmazonOrderId());
        return rma;
    }

    @Override
    public PageResult<Rma> listRmas(Long shopId, String status, PageRequest page) {
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<Rma> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Rma::getShopId, shopId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Rma::getStatus, status);
        }
        if (req.hasCursor()) {
            wrapper.lt(Rma::getId, req.cursorId());
        }
        wrapper.orderByDesc(Rma::getId)
               .last("LIMIT " + req.probeSize());
        List<Rma> rows = rmaMapper.selectList(wrapper);
        PageResult<Rma> result = PageResult.of(rows, req.size(),
                t -> PageRequest.encodeCursor(t.getId()));
        if (result.truncated()) {
            log.warn("RMA 列表被分页截断：shopId={} status={} size={}", shopId, status, req.size());
        }
        return result;
    }

    @Override
    public Rma updateRmaStatus(Long rmaId, String status) {
        Rma rma = support.mustGetAuthorizedRma(rmaId);
        rma.setStatus(status);
        rmaMapper.updateById(rma);
        return rma;
    }

    @Override
    public Rma getRma(Long rmaId) {
        return support.mustGetAuthorizedRma(rmaId);
    }
}
