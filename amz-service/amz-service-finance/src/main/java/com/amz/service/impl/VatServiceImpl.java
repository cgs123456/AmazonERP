package com.amz.service.impl;

import com.amz.mapper.AccountingVoucherMapper;
import com.amz.service.VatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class VatServiceImpl implements VatService {

    @Autowired
    private AccountingVoucherMapper voucherMapper;

    private static final Map<String, BigDecimal> VAT_RATES = new LinkedHashMap<>();
    private static final Map<String, BigDecimal> THRESHOLDS = new LinkedHashMap<>();
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.20");

    static {
        VAT_RATES.put("DE", new BigDecimal("0.19"));
        VAT_RATES.put("FR", new BigDecimal("0.20"));
        VAT_RATES.put("IT", new BigDecimal("0.22"));
        VAT_RATES.put("ES", new BigDecimal("0.21"));
        VAT_RATES.put("UK", new BigDecimal("0.20"));
        VAT_RATES.put("NL", new BigDecimal("0.21"));
        VAT_RATES.put("SE", new BigDecimal("0.25"));
        VAT_RATES.put("PL", new BigDecimal("0.23"));

        THRESHOLDS.put("DE", new BigDecimal("10000"));
        THRESHOLDS.put("FR", new BigDecimal("10000"));
        THRESHOLDS.put("IT", new BigDecimal("35000"));
        THRESHOLDS.put("ES", new BigDecimal("35000"));
        THRESHOLDS.put("UK", new BigDecimal("70000"));
    }

    @Override
    public BigDecimal calculateVat(BigDecimal amount, String country) {
        if (amount == null || country == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = getVatRate(country);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal getVatRate(String country) {
        if (country == null) {
            return DEFAULT_RATE;
        }
        return VAT_RATES.getOrDefault(country.toUpperCase(), DEFAULT_RATE);
    }

    @Override
    public boolean exceedsThreshold(String country, BigDecimal annualSales) {
        if (country == null || annualSales == null) {
            return false;
        }
        BigDecimal threshold = THRESHOLDS.get(country.toUpperCase());
        return threshold != null && annualSales.compareTo(threshold) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> monthlyClosing(Long shopId, int year, int month) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("year", year);
        result.put("month", month);

        try {
            var voucher = new com.amz.model.AccountingVoucher();
            voucher.setShopId(shopId);
            voucher.setVoucherNo("MC-" + year + String.format("%02d", month) + "-" + shopId);
            voucher.setBizDate(String.format("%d-%02d-01", year, month));
            voucher.setSummary("月度结账 " + year + "-" + String.format("%02d", month));
            voucher.setSourceType("MONTHLY_CLOSING");
            voucher.setCurrency("CNY");
            voucher.setCnyAmount(BigDecimal.ZERO);
            voucher.setOriginalAmount(BigDecimal.ZERO);
            voucherMapper.insert(voucher);

            result.put("voucherId", voucher.getId());
            result.put("voucherNo", voucher.getVoucherNo());
            result.put("status", "SUCCESS");
        } catch (Exception e) {
            log.error("月度结账失败 shopId={} year={} month={}", shopId, year, month, e);
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
        }

        return result;
    }
}
