package com.amz.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * VAT 增值税服务接口。
 * <p>
 * 支持多国税率计算、远程销售阈值检查、月度结账。
 */
public interface VatService {

    /** 计算VAT税额 */
    BigDecimal calculateVat(BigDecimal amount, String country);

    /** 获取税率 */
    BigDecimal getVatRate(String country);

    /** 检查是否超过远程销售阈值 */
    boolean exceedsThreshold(String country, BigDecimal annualSales);

    /** 月度结账 */
    Map<String, Object> monthlyClosing(Long shopId, int year, int month);
}
