package com.amz.client;

import com.amz.client.dto.FeeEstimate;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SP-API Fees API（v0）真实客户端：FBA 费用预估。
 * <p>
 * POST /products/fees/v0/feesEstimate?MarketplaceId=...，
 * 请求体 FeesEstimateRequestList（IdType=ASIN）。
 * 响应 FeeDetailList 中按 FeeType 拆项：
 * ReferralFee（佣金）、FBAFulfillmentFee（配送费）、其余并入 otherFees。
 * <p>
 * 费用预估（本接口）与实际扣费（结算原表）的比对是索赔候选识别的数据基础。
 */
@Component
@Profile("!mock")
public class FeesRealClient implements FeesClient {

    private static final Logger log = LoggerFactory.getLogger(FeesRealClient.class);

    private static final String FEES_PATH = "/products/fees/v0/feesEstimate";

    /** Fees 端点标识，用于限流指标维度。 */
    private static final String FEES_ENDPOINT = "fees";

    @Autowired
    private SpApiGateway gateway;

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
        SpApiGateway.ResolvedShop shop = gateway.resolveShop(shopId, marketplaceId);

        JsonObject listingPrice = new JsonObject();
        listingPrice.addProperty("Amount", price.toPlainString());
        listingPrice.addProperty("CurrencyCode", currency == null ? "USD" : currency);
        JsonObject priceToEstimate = new JsonObject();
        priceToEstimate.add("ListingPrice", listingPrice);
        JsonObject request = new JsonObject();
        request.addProperty("IdType", type);
        request.addProperty("IdValue", idValue);
        request.add("PriceToEstimateFees", priceToEstimate);
        request.addProperty("Identifier", "amz-erp-" + shopId + "-" + System.currentTimeMillis());
        JsonArray requestList = new JsonArray();
        requestList.add(request);
        JsonObject body = new JsonObject();
        body.add("FeesEstimateRequestList", requestList);

        JsonObject resp = gateway.callJson("POST", shop, FEES_ENDPOINT, FEES_PATH,
                SpApiGateway.canonicalQuery(Map.of("MarketplaceId", marketplaceId)),
                body.toString());

        FeeEstimate estimate = new FeeEstimate();
        if (ID_TYPE_ASIN.equals(type)) {
            estimate.setAsin(idValue);
            estimate.setSku(sku);
        } else {
            estimate.setSku(sku == null ? idValue : sku);
        }
        estimate.setPrice(price);
        estimate.setCurrency(currency == null ? "USD" : currency);

        BigDecimal referral = BigDecimal.ZERO;
        BigDecimal fulfillment = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;

        JsonObject payload = resp.has("payload") && resp.get("payload").isJsonObject()
                ? resp.getAsJsonObject("payload") : null;
        JsonArray results = payload != null && payload.has("FeesEstimateResultList")
                && payload.get("FeesEstimateResultList").isJsonArray()
                ? payload.getAsJsonArray("FeesEstimateResultList") : null;
        if (results != null && results.size() > 0) {
            JsonObject result = results.get(0).getAsJsonObject();
            JsonObject feesEstimate = result.has("FeesEstimate")
                    && result.get("FeesEstimate").isJsonObject()
                    ? result.getAsJsonObject("FeesEstimate") : null;
            if (feesEstimate != null && feesEstimate.has("FeeDetailList")
                    && feesEstimate.get("FeeDetailList").isJsonArray()) {
                for (JsonElement el : feesEstimate.getAsJsonArray("FeeDetailList")) {
                    JsonObject detail = el.getAsJsonObject();
                    String feeType = str(detail, "FeeType");
                    BigDecimal amount = amount(detail, "FeeAmount");
                    if (amount == null) {
                        continue;
                    }
                    if (feeType != null && feeType.contains("Referral")) {
                        referral = referral.add(amount);
                    } else if (feeType != null && feeType.contains("Fulfillment")) {
                        fulfillment = fulfillment.add(amount);
                    } else {
                        other = other.add(amount);
                    }
                }
            }
        }
        estimate.setReferralFee(referral);
        estimate.setFulfillmentFee(fulfillment);
        estimate.setOtherFees(other);
        estimate.setTotalFees(referral.add(fulfillment).add(other));
        estimate.setEstimatedNet(price.subtract(estimate.getTotalFees()));
        log.info("estimateFbaFees shopId={} idType={} idValue={} price={} referral={} fulfillment={} other={}",
                shopId, type, idValue, price, referral, fulfillment, other);
        return estimate;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).isJsonObject() ? null : o.get(key).getAsString();
    }

    private static BigDecimal amount(JsonObject holder, String key) {
        if (holder == null || !holder.has(key) || !holder.get(key).isJsonObject()) {
            return null;
        }
        JsonObject amountObj = holder.getAsJsonObject(key);
        String s = str(amountObj, "Amount");
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
