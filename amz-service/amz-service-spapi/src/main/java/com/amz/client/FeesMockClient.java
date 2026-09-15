package com.amz.client;

import com.amz.client.dto.FeeEstimate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SP-API Fees 模拟客户端。
 * <p>
 * 仅在 {@code spring.profiles.active=mock} 时生效。
 * 确定性计价：佣金按售价 15%；配送费按价格分档（≤$10 → 3.31、≤$20 → 4.68、其余 → 5.87），
 * 与 standard 尺寸档的大致量级一致 —— 用于费用差异比对链路的形态验证，
 * 非真实费率表。
 */
@Component
@Profile("mock")
public class FeesMockClient implements FeesClient {

    private static final Logger log = LoggerFactory.getLogger(FeesMockClient.class);

    /** 模拟佣金比例（standard 类目大致水平）。 */
    private static final BigDecimal REFERRAL_RATE = new BigDecimal("0.15");

    @Override
    public FeeEstimate estimateFbaFees(Long shopId, String marketplaceId, String idType, String idValue,
                                       String sku, BigDecimal price, String currency) {
        String type = idType == null || idType.isBlank() ? ID_TYPE_ASIN : idType.trim().toUpperCase();
        if (!ID_TYPE_ASIN.equals(type) && !ID_TYPE_SKU.equals(type)) {
            throw new IllegalArgumentException("unsupported idType: " + idType);
        }
        if (idValue == null || idValue.isBlank()) {
            throw new IllegalArgumentException("idValue (ASIN or SKU) must not be blank");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive: " + price);
        }
        BigDecimal referral = price.multiply(REFERRAL_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fulfillment = fulfillmentTier(price);
        BigDecimal total = referral.add(fulfillment);

        FeeEstimate estimate = new FeeEstimate();
        if (ID_TYPE_ASIN.equals(type)) {
            estimate.setAsin(idValue);
            estimate.setSku(sku);
        } else {
            // 按 SKU 估价时 idValue 即 SKU，无 ASIN 可填
            estimate.setSku(sku == null ? idValue : sku);
        }
        estimate.setPrice(price);
        estimate.setCurrency(currency == null ? "USD" : currency);
        estimate.setReferralFee(referral);
        estimate.setFulfillmentFee(fulfillment);
        estimate.setOtherFees(BigDecimal.ZERO);
        estimate.setTotalFees(total);
        estimate.setEstimatedNet(price.subtract(total));
        log.info("estimateFbaFees (mock) shopId={} idType={} idValue={} price={} total={}",
                shopId, type, idValue, price, total);
        return estimate;
    }

    private static BigDecimal fulfillmentTier(BigDecimal price) {
        if (price.compareTo(new BigDecimal("10")) <= 0) {
            return new BigDecimal("3.31");
        }
        if (price.compareTo(new BigDecimal("20")) <= 0) {
            return new BigDecimal("4.68");
        }
        return new BigDecimal("5.87");
    }
}
