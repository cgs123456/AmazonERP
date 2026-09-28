package com.amz.service;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

import com.amz.dto.PaymentCollectionSummary;
import com.amz.model.PaymentCollection;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单级回款台账服务（T05）。
 */
public interface PaymentCollectionService {

    /**
     * 按店铺重算回款台账（从结算明细聚合）。
     * <p>
     * 幂等：按 shopId + amazonOrderId 覆盖更新，已由费用比对写入的短款字段被保留而非清零。
     *
     * @return 参与重算的订单数
     */
    int rebuild(Long shopId);

    /**
     * 查询回款台账（可按状态过滤）。
     */
    PageResult<PaymentCollection> list(Long shopId, String status, PageRequest page);

    /**
     * 回款概览（在途未回金额与已回短款金额分别列示，不互抵）。
     */
    PaymentCollectionSummary summary(Long shopId);

    /**
     * 写入订单短款金额（由费用比对链路调用），并据其刷新回款状态。
     *
     * @return 该订单台账存在并更新成功返回 true；不存在返回 false
     */
    boolean applyShortfall(Long shopId, String amazonOrderId, BigDecimal shortfall);
}
