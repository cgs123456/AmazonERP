package com.amz.parse;

import com.amz.model.SettlementDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结算行归类口径测试。
 * <p>
 * 回款聚合与凭证生成现在都走这里，所以这张表就是两套「钱」的账是否对得上的唯一依据：
 * 改任何一行归类，必须同时想清楚对 PaymentCollection 和 SettlementVoucher 两边的影响。
 */
@DisplayName("结算行归类口径")
class SettlementClassifierTest {

    private SettlementDetail row(String txType, String amountType) {
        SettlementDetail d = new SettlementDetail();
        d.setTransactionType(txType);
        d.setAmountType(amountType);
        return d;
    }

    @Test
    @DisplayName("Order+Principal 是销售本金，Order 下其它分项是平台扣费")
    void orderSplitsPrincipalAndFee() {
        assertEquals(SettlementClassifier.Kind.PRINCIPAL, SettlementClassifier.classify(row("Order", "Principal")));
        assertEquals(SettlementClassifier.Kind.FEE, SettlementClassifier.classify(row("Order", "Commission")));
        assertEquals(SettlementClassifier.Kind.FEE, SettlementClassifier.classify(row("Order", "Fees")));
        assertEquals(SettlementClassifier.Kind.FEE, SettlementClassifier.classify(row("Order", null)));
    }

    @Test
    @DisplayName("Refund / Adjustment 各自成类，ServiceFee 与未知类型按扣费处理")
    void otherTypes() {
        assertEquals(SettlementClassifier.Kind.REFUND, SettlementClassifier.classify(row("Refund", "Refund")));
        assertEquals(SettlementClassifier.Kind.ADJUSTMENT, SettlementClassifier.classify(row("Adjustment", null)));
        assertEquals(SettlementClassifier.Kind.FEE, SettlementClassifier.classify(row("ServiceFee", null)));
        assertEquals(SettlementClassifier.Kind.FEE, SettlementClassifier.classify(row("Liquidations", null)));
        assertEquals(SettlementClassifier.Kind.FEE, SettlementClassifier.classify(row(null, null)));
    }

    @Test
    @DisplayName("大小写与空格不敏感（亚马逊报表历史上出现过 Transfer/refund 这类写法）")
    void caseInsensitive() {
        assertEquals(SettlementClassifier.Kind.REFUND, SettlementClassifier.classify(row("refund", null)));
        assertEquals(SettlementClassifier.Kind.PRINCIPAL, SettlementClassifier.classify(row("ORDER", "principal")));
    }

    @Test
    @DisplayName("只有扣费与退款会出凭证，本金和调整各归别的链路")
    void onlyFeeAndRefundAreVoucherizable() {
        assertTrue(SettlementClassifier.voucherizable(SettlementClassifier.Kind.FEE));
        assertTrue(SettlementClassifier.voucherizable(SettlementClassifier.Kind.REFUND));
        assertFalse(SettlementClassifier.voucherizable(SettlementClassifier.Kind.PRINCIPAL));
        assertFalse(SettlementClassifier.voucherizable(SettlementClassifier.Kind.ADJUSTMENT));
    }

    @Test
    @DisplayName("空行抛错而不是默默归类")
    void nullRowThrows() {
        assertThrows(IllegalArgumentException.class, () -> SettlementClassifier.classify(null));
    }
}
