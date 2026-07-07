package com.amz.service;

import com.amz.model.AccountingVoucher;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业财一体化服务接口。
 */
public interface FinanceService {

    /**
     * 根据订单自动生成会计凭证（借应收 / 贷收入 + 多币种换算）。
     *
     * @param shopId    店铺 ID
     * @param orderNo   订单号
     * @param amount    订单金额（原币）
     * @param currency  币种
     * @return 生成的凭证
     */
    AccountingVoucher generateOrderVoucher(Long shopId, String orderNo, BigDecimal amount, String currency);

    /**
     * 同步凭证到金蝶。
     */
    boolean syncToKingdee(Long voucherId);

    /**
     * 查询店铺凭证列表。
     */
    List<AccountingVoucher> listVouchers(Long shopId, String sourceType);

    /**
     * 查询店铺某时间段内的总利润（收入 - 成本 - 费用，CNY）。
     */
    BigDecimal calculateProfit(Long shopId, String startDate, String endDate);
}
