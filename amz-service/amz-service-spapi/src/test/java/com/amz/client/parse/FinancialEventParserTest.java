package com.amz.client.parse;

import com.amz.client.dto.FinancialEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 财务事件解析器单元测试。
 * <p>
 * 覆盖：四类事件（INCOME / FEE / REFUND / ADJUSTMENT）的字段路径与符号约定、
 * 幂等键确定性（重复解析生成相同 key）、空载荷、脏数据跳过。
 * 无凭证环境下，这是「SP-API 字段路径是否写对」唯一能被验证的层。
 */
@DisplayName("FinancialEventParser 财务事件解析测试")
class FinancialEventParserTest {

    @Test
    @DisplayName("ShipmentEvent：Principal 记为 INCOME（正），ItemFeeList 记为 FEE（负）")
    void parseShipmentEvent() {
        JsonObject events = JsonParser.parseString("""
                {
                  "ShipmentEventList": [{
                    "AmazonOrderId": "111-0000001-0000001",
                    "PostedDate": "2026-09-02T12:00:00Z",
                    "ShipmentItemList": [{
                      "SellerSKU": "SKU-ALPHA",
                      "ASIN": "B0ALPHA",
                      "OrderItemId": "ITEM-1",
                      "ItemChargeList": [
                        {"ChargeType": "Principal", "ChargeAmount": {"CurrencyCode": "USD", "Amount": "29.99"}},
                        {"ChargeType": "Shipping", "ChargeAmount": {"CurrencyCode": "USD", "Amount": "3.99"}}
                      ],
                      "ItemFeeList": [
                        {"ChargeType": "Commission", "ChargeAmount": {"CurrencyCode": "USD", "Amount": "-4.50"}},
                        {"ChargeType": "FBAFulfillmentFee", "ChargeAmount": {"CurrencyCode": "USD", "Amount": "-5.03"}}
                      ]
                    }]
                  }]
                }
                """).getAsJsonObject();

        List<FinancialEvent> out = FinancialEventParser.parse(events);
        Map<String, List<FinancialEvent>> byType = out.stream()
                .collect(Collectors.groupingBy(FinancialEvent::getType));

        assertEquals(1, byType.get(FinancialEvent.TYPE_INCOME).size());
        FinancialEvent income = byType.get(FinancialEvent.TYPE_INCOME).get(0);
        assertEquals(new BigDecimal("29.99"), income.getAmount());
        assertEquals("USD", income.getCurrency());
        assertEquals("SKU-ALPHA", income.getSku());
        assertEquals("B0ALPHA", income.getAsin());
        assertEquals("111-0000001-0000001", income.getAmazonOrderId());
        assertEquals("2026-09-02T12:00:00Z", income.getPostedAt());

        assertEquals(2, byType.get(FinancialEvent.TYPE_FEE).size());
        // 非 Principal 的 charge（如 Shipping）不记为收入，也不记为费用
        assertEquals(3, out.size());
    }

    @Test
    @DisplayName("RefundEvent：Principal 记为 REFUND（取负）")
    void parseRefundEvent() {
        JsonObject events = JsonParser.parseString("""
                {
                  "RefundEventList": [{
                    "AmazonOrderId": "111-0000002-0000002",
                    "PostedDate": "2026-09-05T12:00:00Z",
                    "ShipmentItemList": [{
                      "SellerSKU": "SKU-BETA",
                      "ItemChargeList": [
                        {"ChargeType": "Principal", "ChargeAmount": {"CurrencyCode": "USD", "Amount": "49.99"}}
                      ]
                    }]
                  }]
                }
                """).getAsJsonObject();

        List<FinancialEvent> out = FinancialEventParser.parse(events);
        assertEquals(1, out.size());
        FinancialEvent refund = out.get(0);
        assertEquals(FinancialEvent.TYPE_REFUND, refund.getType());
        assertEquals(new BigDecimal("-49.99"), refund.getAmount());
    }

    @Test
    @DisplayName("AdjustmentEvent：FBA 库存赔付保留正向符号（索赔闭环关键来源）")
    void parseAdjustmentEvent() {
        JsonObject events = JsonParser.parseString("""
                {
                  "AdjustmentEventList": [{
                    "PostedDate": "2026-09-06T12:00:00Z",
                    "AdjustmentType": "FBA Inventory Reimbursement - Customer Return",
                    "AdjustmentAmount": {"CurrencyCode": "USD", "Amount": "12.50"}
                  }]
                }
                """).getAsJsonObject();

        List<FinancialEvent> out = FinancialEventParser.parse(events);
        assertEquals(1, out.size());
        FinancialEvent adj = out.get(0);
        assertEquals(FinancialEvent.TYPE_ADJUSTMENT, adj.getType());
        assertEquals(new BigDecimal("12.50"), adj.getAmount());
        assertNull(adj.getAmazonOrderId());
        assertTrue(adj.getFeeType().contains("Reimbursement"));
    }

    @Test
    @DisplayName("幂等键确定性：同一载荷重复解析生成完全相同的 eventId 序列")
    void eventIdIsDeterministic() {
        String payload = """
                {
                  "ShipmentEventList": [{
                    "AmazonOrderId": "111-X",
                    "PostedDate": "2026-09-02T12:00:00Z",
                    "ShipmentItemList": [{
                      "ItemChargeList": [{"ChargeType": "Principal", "ChargeAmount": {"CurrencyCode": "USD", "Amount": "29.99"}}],
                      "ItemFeeList": [{"ChargeType": "Commission", "ChargeAmount": {"CurrencyCode": "USD", "Amount": "-4.50"}}]
                    }]
                  }]
                }
                """;
        List<FinancialEvent> first = FinancialEventParser.parse(
                JsonParser.parseString(payload).getAsJsonObject());
        List<FinancialEvent> second = FinancialEventParser.parse(
                JsonParser.parseString(payload).getAsJsonObject());
        assertEquals(
                first.stream().map(FinancialEvent::getEventId).collect(Collectors.toList()),
                second.stream().map(FinancialEvent::getEventId).collect(Collectors.toList()));
        first.forEach(e -> assertNotNull(e.getEventId()));
    }

    @Test
    @DisplayName("空载荷 / null 返回空列表（无事件窗口不是异常）")
    void parseEmpty() {
        assertTrue(FinancialEventParser.parse(null).isEmpty());
        assertTrue(FinancialEventParser.parse(new JsonObject()).isEmpty());
        assertTrue(FinancialEventParser.parse(JsonParser.parseString(
                "{\"FinancialEvents\": {}}").getAsJsonObject()).isEmpty());
    }

    @Test
    @DisplayName("单条脏数据（Amount 非法）应跳过而非中断整批")
    void parseSkipsDirtyAmount() {
        JsonObject events = JsonParser.parseString("""
                {
                  "AdjustmentEventList": [
                    {"PostedDate": "2026-09-06T12:00:00Z",
                     "AdjustmentType": "OK",
                     "AdjustmentAmount": {"CurrencyCode": "USD", "Amount": "not-a-number"}},
                    {"PostedDate": "2026-09-07T12:00:00Z",
                     "AdjustmentType": "FINE",
                     "AdjustmentAmount": {"CurrencyCode": "USD", "Amount": "1.00"}}
                  ]
                }
                """).getAsJsonObject();

        List<FinancialEvent> out = FinancialEventParser.parse(events);
        assertEquals(1, out.size());
        assertEquals("FINE", out.get(0).getFeeType());
    }
}
