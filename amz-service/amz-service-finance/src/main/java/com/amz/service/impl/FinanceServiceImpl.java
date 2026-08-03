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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

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

    @Autowired
    private com.amz.service.VatService vatService;

    @Override
    public AccountingVoucher generateOrderVoucher(Long shopId, String orderNo, BigDecimal amount, String currency) {
        BigDecimal cnyAmount = currencyConverter.convertToCny(amount, currency);
        BigDecimal rate = currencyConverter.getRate(currency);

        AccountingVoucher v = new AccountingVoucher();
        // 凭证编号：UUID 去横线，规避 "V"+System.currentTimeMillis() 在并发落库时
        // 撞库触发 amz_accounting_voucher.uk_voucher_no 唯一约束的问题。
        v.setVoucherNo("V" + UUID.randomUUID().toString().replace("-", ""));
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
            // 真实金蝶 API 未对接时 KingdeeRealClient 降级返回 KINGDEE_MOCK_ 前缀占位编号，
            // 此时仅标记为 SYNCING（同步中），区别于真实已过账的 SYNCED，
            // 避免 FAILED 误报，待真实 API 接入后自动转为 SYNCED。
            boolean isMock = kingdeeNo != null && kingdeeNo.startsWith("KINGDEE_MOCK_");
            v.setKingdeeSyncStatus(isMock ? "SYNCING" : "SYNCED");
            voucherMapper.updateById(v);
            log.info("凭证同步金蝶完成：voucherId={} kingdeeNo={} status={}", voucherId, kingdeeNo, v.getKingdeeSyncStatus());
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
        // 业财一体化利润汇总：按 sourceType 区分借贷方向加减
        //   ORDER        借应收 / 贷主营业务收入        → 收入（贷方）         + cnyAmount
        //   PROCUREMENT  借库存商品 / 贷应付账款        → 采购成本（借方）     - cnyAmount
        //   PLATFORM_FEE 借销售费用 / 贷银行存款        → 平台费用（借方）     - cnyAmount
        //   REFUND       借销售退回 / 贷应收账款        → 退款（借方冲减收入） - cnyAmount
        // 其他类型忽略（向前兼容）
        LambdaQueryWrapper<AccountingVoucher> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AccountingVoucher::getShopId, shopId);
        if (startDate != null) wrapper.ge(AccountingVoucher::getBizDate, startDate);
        if (endDate != null) wrapper.le(AccountingVoucher::getBizDate, endDate);
        List<AccountingVoucher> vouchers = voucherMapper.selectList(wrapper);

        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;
        for (AccountingVoucher v : vouchers) {
            BigDecimal amount = v.getCnyAmount() == null ? BigDecimal.ZERO : v.getCnyAmount();
            String sourceType = v.getSourceType();
            if ("ORDER".equals(sourceType)) {
                profit = profit.add(amount);
                // VAT deduction: calculate VAT on original sales amount
                BigDecimal originalAmount = v.getOriginalAmount() != null ? v.getOriginalAmount() : BigDecimal.ZERO;
                BigDecimal vatAmount = vatService.calculateVat(originalAmount, v.getCurrency());
                if (vatAmount != null) {
                    totalVat = totalVat.add(vatAmount);
                }
            } else if ("PROCUREMENT".equals(sourceType)
                    || "PLATFORM_FEE".equals(sourceType)
                    || "REFUND".equals(sourceType)) {
                profit = profit.subtract(amount);
            }
        }
        // Deduct total VAT from net profit
        profit = profit.subtract(totalVat);
        log.debug("利润计算 shopId={} 毛利润={} VAT扣除={} 净利润={}", shopId, profit.add(totalVat), totalVat, profit);
        return profit.setScale(2, RoundingMode.HALF_UP);
    }
}