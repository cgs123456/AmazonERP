package com.amz.client;

import com.amz.client.dto.FeeEstimate;
import com.amz.client.dto.FinancialEvent;
import com.amz.credential.ShopCredential;
import com.amz.testsupport.TestCredentials;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SP-API 成功/失败响应语义回归。
 *
 * <p>这些测试锁住三类生产风险：官方字段名错位导致成功响应被解析成零值、
 * 分页 token 位置错误导致静默漏数据、必填响应字段缺失仍返回伪成功。
 */
@DisplayName("SP-API 响应语义失败关闭契约")
class ApiResponseSemanticsContractTest {

    private static final Long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";

    @Test
    @DisplayName("Fees：按官方单件 FeesEstimateResult 结构解析，而非恒返回零")
    void feesParsesOfficialSingularResult() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        when(gateway.resolveShop(SHOP_ID, MARKETPLACE_ID)).thenReturn(resolvedShop());
        when(gateway.callJson(eq("POST"), any(), eq("fees.getMyFeesEstimates"),
                eq("/products/fees/v0/feesEstimate"), anyString(), anyString()))
                .thenReturn(json("{\"payload\":{\"FeesEstimateResult\":{\"Status\":\"Success\","
                        + "\"FeesEstimate\":{\"TimeOfFeesEstimation\":\"2026-01-01T00:00:00Z\","
                        + "\"FeeDetailList\":["
                        + "{\"FeeType\":\"ReferralFee\",\"FeeAmount\":{\"CurrencyCode\":\"USD\",\"Amount\":\"1.50\"}},"
                        + "{\"FeeType\":\"FBAFulfillmentFee\",\"FeeAmount\":{\"CurrencyCode\":\"USD\",\"Amount\":\"2.00\"}}"
                        + "]}}}}"));

        FeesRealClient client = new FeesRealClient();
        ReflectionTestUtils.setField(client, "gateway", gateway);

        FeeEstimate estimate = client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID, "ASIN", "B000TEST",
                "SKU-1", new BigDecimal("20.00"), "USD");

        assertEquals(new BigDecimal("1.50"), estimate.getReferralFee());
        assertEquals(new BigDecimal("2.00"), estimate.getFulfillmentFee());
        assertEquals(new BigDecimal("3.50"), estimate.getTotalFees());
        assertEquals(new BigDecimal("16.50"), estimate.getEstimatedNet());
    }

    @Test
    @DisplayName("Fees：ClientError 必须失败关闭，不能返回全零费用")
    void feesClientErrorFailsClosed() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        when(gateway.resolveShop(SHOP_ID, MARKETPLACE_ID)).thenReturn(resolvedShop());
        when(gateway.callJson(eq("POST"), any(), eq("fees.getMyFeesEstimates"),
                eq("/products/fees/v0/feesEstimate"), anyString(), anyString()))
                .thenReturn(json("{\"payload\":{\"FeesEstimateResult\":{\"Status\":\"ClientError\","
                        + "\"Error\":{\"Type\":\"Sender\",\"Code\":\"InvalidParameterValue\","
                        + "\"Message\":\"Invalid ASIN\",\"Detail\":{}}}}}"));
        FeesRealClient client = new FeesRealClient();
        ReflectionTestUtils.setField(client, "gateway", gateway);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.estimateFbaFees(SHOP_ID, MARKETPLACE_ID, "ASIN", "B000TEST",
                        "SKU-1", new BigDecimal("20.00"), "USD"));

        assertTrue(ex.getMessage().contains("ClientError"), ex.getMessage());
        assertTrue(ex.getMessage().contains("InvalidParameterValue"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Invalid ASIN"), ex.getMessage());
    }

    @Test
    @DisplayName("Finances：从 payload.NextToken 继续翻页，不能静默只取第一页")
    void financesFollowsPayloadNextToken() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        when(gateway.resolveShop(SHOP_ID, null)).thenReturn(resolvedShop());
        when(gateway.callJson(eq("GET"), any(), eq("finances.listFinancialEvents"),
                eq("/finances/v0/financialEvents"), anyString(), isNull()))
                .thenReturn(
                        json("{\"payload\":{\"NextToken\":\"page-2\",\"FinancialEvents\":{"
                                + "\"AdjustmentEventList\":[{\"PostedDate\":\"2026-01-01T00:00:00Z\","
                                + "\"AdjustmentType\":\"FIRST\",\"AdjustmentAmount\":{\"CurrencyCode\":\"USD\",\"CurrencyAmount\":\"1.00\"}}]}}}"),
                        json("{\"payload\":{\"FinancialEvents\":{"
                                + "\"AdjustmentEventList\":[{\"PostedDate\":\"2026-01-02T00:00:00Z\","
                                + "\"AdjustmentType\":\"SECOND\",\"AdjustmentAmount\":{\"CurrencyCode\":\"USD\",\"CurrencyAmount\":\"2.00\"}}]}}}"));
        FinancesRealClient client = new FinancesRealClient();
        ReflectionTestUtils.setField(client, "gateway", gateway);

        List<FinancialEvent> events = client.listFinancialEvents(SHOP_ID, "2026-01-01", "2026-02-01");

        assertEquals(2, events.size());
        assertEquals("FIRST", events.get(0).getFeeType());
        assertEquals("SECOND", events.get(1).getFeeType());
        verify(gateway, times(2)).callJson(eq("GET"), any(), eq("finances.listFinancialEvents"),
                eq("/finances/v0/financialEvents"), anyString(), isNull());
    }

    @Test
    @DisplayName("Finances：HTTP 200 但缺 payload 时必须失败关闭")
    void financesMissingPayloadFailsClosed() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        when(gateway.resolveShop(SHOP_ID, null)).thenReturn(resolvedShop());
        when(gateway.callJson(eq("GET"), any(), eq("finances.listFinancialEvents"),
                eq("/finances/v0/financialEvents"), anyString(), isNull()))
                .thenReturn(json("{}"));
        FinancesRealClient client = new FinancesRealClient();
        ReflectionTestUtils.setField(client, "gateway", gateway);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.listFinancialEvents(SHOP_ID, "2026-01-01", "2026-02-01"));

        assertTrue(ex.getMessage().contains("payload"), ex.getMessage());
    }

    @Test
    @DisplayName("Reports：createReport 缺官方必填 reportId 时必须失败关闭")
    void reportsMissingRequiredReportIdFailsClosed() {
        SpApiGateway gateway = mock(SpApiGateway.class);
        when(gateway.resolveShop(SHOP_ID, MARKETPLACE_ID)).thenReturn(resolvedShop());
        when(gateway.callJson(eq("POST"), any(), eq("reports.createReport"),
                eq("/reports/2021-06-30/reports"), isNull(), anyString()))
                .thenReturn(json("{}"));
        ReportsRealClient client = new ReportsRealClient(gateway);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.createReport(SHOP_ID, MARKETPLACE_ID,
                        "GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"));

        assertTrue(ex.getMessage().contains("reportId"), ex.getMessage());
    }

    @Test
    @DisplayName("Reports：getReport 缺任一官方必填字段时必须失败关闭")
    void reportsGetMissingRequiredFieldsFailsClosed() {
        for (String missing : List.of("reportId", "reportType", "processingStatus", "createdTime")) {
            assertMissingRequiredReportFieldFailsClosed(missing);
        }
    }

    private static void assertMissingRequiredReportFieldFailsClosed(String missing) {
        SpApiGateway gateway = mock(SpApiGateway.class);
        when(gateway.resolveShop(SHOP_ID, null)).thenReturn(resolvedShop());
        JsonObject response = json("{\"reportId\":\"r-1\","
                + "\"reportType\":\"GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE\","
                + "\"processingStatus\":\"DONE\","
                + "\"createdTime\":\"2026-01-01T00:00:00Z\"}");
        response.remove(missing);
        when(gateway.callJson(eq("GET"), any(), eq("reports.getReport"),
                eq("/reports/2021-06-30/reports/r-1"), isNull(), isNull()))
                .thenReturn(response);
        ReportsRealClient client = new ReportsRealClient(gateway);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.getReport(SHOP_ID, "r-1"),
                "缺少官方必填字段 " + missing + " 时不得返回部分 ReportInfo");

        assertTrue(ex.getMessage().contains(missing), ex.getMessage());
    }

    private static SpApiGateway.ResolvedShop resolvedShop() {
        ShopCredential credential = TestCredentials.northAmerica();
        return new SpApiGateway.ResolvedShop(credential, MARKETPLACE_ID, "NA",
                "https://sellingpartnerapi-na.amazon.com",
                "sellingpartnerapi-na.amazon.com", "us-east-1");
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}