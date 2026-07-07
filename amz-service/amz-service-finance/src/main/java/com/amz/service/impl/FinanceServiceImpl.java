package com.amz.service.impl;

import com.amz.client.KingdeeClient;
import com.amz.finance.CurrencyConverter;
import com.amz.mapper.AccountingVoucherMapper;
import com.amz.model.AccountingVoucher;
import com.amz.service.FinanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 业财一体化服务实现。
 * <p>
 * 自动凭证生成 + 多币种核算 + 金蝶同步。
 */
@Slf4j
@Service
public class FinanceServiceImpl implements FinanceService {

    /** 会计科目代码（参考金蝶标准科目） */
    private static final String ACCT_RECEIVABLE = "1122";      // 应收账款
    private static final String ACCT_MAIN_REVENUE = "6001";    // 主营业务收入
    private static final String ACCT_INVENTORY = "1405";       // 库存商品
    private static final String ACCT_PAYABLE = "2202";         // 应付账款
    private static final String ACCT_SALES_FEE = "6601";       // 销售费用
    private static final String ACCT_BANK = "1002";            // 银行存款

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private AccountingVoucherMapper voucherMapper;

    @Autowired
    private CurrencyConverter currencyConverter;

    @Autowired
    private KingdeeClient kingdeeClient;

    @Override
    public AccountingVoucher generateOrderVoucher(Long shopId, String orderNo, BigDecimal amount, String currency) {
        BigDecimal cnyAmount = currencyConverter.convertToCny(amount, currency);
        BigDecimal rate = currencyConverter.getRate(currency);

        AccountingVoucher v = new AccountingVoucher();
        v.setVoucherNo("V" + System.currentTimeMillis());
        v.setShopId(shopId);
        v.setBizDate(LocalDate.now().format(FMT));
        v.setSummary("订单销售 - " + orderNo);
        v.setDebitAccount(ACCT_RECEIVABLE);
        v.setCreditAccount(ACCT_MAIN_REVENUE);
        v.setOriginalAmount(amount);
        v.setCurrency(currency);
        v.setExchangeRate(rate);
        v.setCnyAmount(cnyAmount);
        v.setSourceType("ORDER");
        v.setSourceNo(orderNo);
        v.setKingdeeSyncStatus("PENDING");
        voucherMapper.insert(v);
        log.info("订单凭证生成：orderNo={} 原币 {} {} → CNY {}", orderNo, amount, currency, cnyAmount);
        return v;
    }

    @Override
    public boolean syncToKingdee(Long voucherId) {
        AccountingVoucher v = voucherMapper.selectById(voucherId);
        if (v == null) {
            return false;
        }
        try {
            String kingdeeNo = kingdeeClient.syncVoucher(v);
            v.setKingdeeSyncStatus("SYNCED");
            voucherMapper.updateById(v);
            log.info("凭证同步金蝶成功：voucherId={} kingdeeNo={}", voucherId, kingdeeNo);
            return true;
        } catch (Exception e) {
            v.setKingdeeSyncStatus("FAILED");
            voucherMapper.updateById(v);
            log.error("凭证同步金蝶失败：voucherId={}", voucherId, e);
            return false;
        }
    }

    @Override
    public List<AccountingVoucher> listVouchers(Long shopId, String sourceType) {
        LambdaQueryWrapper<AccountingVoucher> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AccountingVoucher::getShopId, shopId);
        if (sourceType != null && !sourceType.isBlank()) {
            wrapper.eq(AccountingVoucher::getSourceType, sourceType);
        }
        wrapper.orderByDesc(AccountingVoucher::getId);
        return voucherMapper.selectList(wrapper);
    }

    @Override
    public BigDecimal calculateProfit(Long shopId, String startDate, String endDate) {
        // 简化利润计算：收入(ORDER) - 费用(PLATFORM_FEE)
        // 生产环境应按科目分类汇总借贷
        LambdaQueryWrapper<AccountingVoucher> revenueWrapper = new LambdaQueryWrapper<>();
        revenueWrapper.eq(AccountingVoucher::getShopId, shopId)
                .eq(AccountingVoucher::getSourceType, "ORDER");
        if (startDate != null) revenueWrapper.ge(AccountingVoucher::getBizDate, startDate);
        if (endDate != null) revenueWrapper.le(AccountingVoucher::getBizDate, endDate);
        List<AccountingVoucher> revenueVouchers = voucherMapper.selectList(revenueWrapper);
        BigDecimal revenue = revenueVouchers.stream()
                .map(AccountingVoucher::getCnyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LambdaQueryWrapper<AccountingVoucher> feeWrapper = new LambdaQueryWrapper<>();
        feeWrapper.eq(AccountingVoucher::getShopId, shopId)
                .eq(AccountingVoucher::getSourceType, "PLATFORM_FEE");
        if (startDate != null) feeWrapper.ge(AccountingVoucher::getBizDate, startDate);
        if (endDate != null) feeWrapper.le(AccountingVoucher::getBizDate, endDate);
        List<AccountingVoucher> feeVouchers = voucherMapper.selectList(feeWrapper);
        BigDecimal fee = feeVouchers.stream()
                .map(AccountingVoucher::getCnyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return revenue.subtract(fee);
    }
}
