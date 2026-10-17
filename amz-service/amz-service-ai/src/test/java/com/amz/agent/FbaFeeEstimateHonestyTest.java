package com.amz.agent;

import com.amz.client.ProductServiceClient;
import com.amz.context.UserContext;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FBA 费用估算的降级值必须在**给 LLM 的那句话**里说出来。
 *
 * <p><b>缺陷</b>：{@code ProductController.estimateFbaFees} 在费率表未命中或订单
 * 服务不可达时，回退到内置常量（履约费 3.22 / 5.45，仓储 0.87/kg），
 * 并在 `data.source` 里标 `estimated`。但 Agent 工具的 message 只拼接了三个金额：
 *
 * <pre>SKU X FBA 费用预估（standard, 0.50 kg）：履约费 3.22 + 仓储费 0.44 = 3.66</pre>
 *
 * <p>模型只会把这句话讲给运营听，`data.source` 它看不见。于是「费率表没配、
 * 按内置常量估算」会被复述成「FBA 履约费 3.22」——一个看起来精确、实际与
 * 该店铺、该 ASIN 都无关的数字。
 *
 * <p>判据：source 不是 {@code real} 时，message 必须带免责说明；
 * 是 {@code real} 时**不能**带（否则真实数据也被打上「估算」标签，同样是误导）。
 */
@DisplayName("FBA 费用估算：估算值不得被讲成实际扣费")
class FbaFeeEstimateHonestyTest {

    private ErpToolExecutor executor;
    private ProductServiceClient productServiceClient;

    @BeforeEach
    void setUp() {
        productServiceClient = mock(ProductServiceClient.class);
        ErpProductTools productTools = new ErpProductTools();
        ReflectionTestUtils.setField(productTools, "productServiceClient", productServiceClient);

        executor = new ErpToolExecutor();
        ReflectionTestUtils.setField(executor, "productTools", productTools);
        ReflectionTestUtils.setField(executor, "orderTools", new ErpOrderTools());
        ReflectionTestUtils.setField(executor, "inventoryTools", new ErpInventoryTools());
        ReflectionTestUtils.setField(executor, "adTools", new ErpAdTools());
        ReflectionTestUtils.setField(executor, "analysisTools", new ErpAnalysisTools());
        ReflectionTestUtils.setField(executor, "logisticsTools", new ErpLogisticsTools());
        ReflectionTestUtils.setField(executor, "procurementTools", new ErpProcurementTools());

        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShopId(1L);
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private String messageOf(String toolResult) {
        JsonObject json = JsonParser.parseString(toolResult).getAsJsonObject();
        return json.get("message").getAsString();
    }

    private void stubFeeResponse(String source) {
        Map<String, Object> data = new HashMap<>();
        data.put("fulfillmentFee", 3.22);
        data.put("storageFee", 0.44);
        data.put("totalFbaFee", 3.66);
        data.put("source", source);
        when(productServiceClient.estimateFbaFees(any(), any(), anyDouble(), anyString()))
                .thenReturn(Result.success(data));
    }

    private String callTool() {
        FunctionCall call = new FunctionCall();
        call.setName("estimate_fba_fees");
        call.setArguments(new HashMap<>(Map.of("shopId", 1, "sku", "SKU-1", "weight", 0.5)));
        return executor.execute(call);
    }

    @Test
    @DisplayName("source=estimated 时，message 必须说明这是内置估算")
    void estimatedSourceIsDisclosedInMessage() {
        stubFeeResponse("estimated");

        String message = messageOf(callTool());

        assertTrue(message.contains("内置估算"), "估算值必须自曝来源，实际：" + message);
        assertTrue(message.contains("不是平台实际扣费"), "实际：" + message);
        assertTrue(message.contains("source=estimated"), "实际：" + message);
    }

    @Test
    @DisplayName("source=estimated_storage_only 时同样要披露")
    void storageOnlyEstimateIsAlsoDisclosed() {
        stubFeeResponse("estimated_storage_only");

        String message = messageOf(callTool());

        assertTrue(message.contains("内置估算"), "实际：" + message);
        assertTrue(message.contains("estimated_storage_only"), "实际：" + message);
    }

    @Test
    @DisplayName("source=real 时不得打估算标签——那同样是误导")
    void realSourceIsNotLabelledAsEstimate() {
        stubFeeResponse("real");

        String message = messageOf(callTool());

        assertFalse(message.contains("内置估算"), "真实费率不该被标成估算，实际：" + message);
        assertFalse(message.contains("不是平台实际扣费"), "实际：" + message);
        assertTrue(message.contains("3.66"), "金额仍要在，实际：" + message);
    }
}
