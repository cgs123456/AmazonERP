package com.amz.client;

import com.amz.exception.ConnectorException;
import com.amz.http.ResilientHttpClient;
import com.amz.model.AccountingVoucher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KingdeeRealClientTest {

    private static final String LOGIN_PATH =
            "/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc";
    private static final String SAVE_PATH =
            "/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Save.common.kdsvc";
    private static final String QUERY_PATH =
            "/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc";

    @Mock
    private ResilientHttpClient http;

    private KingdeeProperties properties;
    private KingdeeRealClient client;

    @BeforeEach
    void setUp() {
        properties = new KingdeeProperties();
        properties.setApiGateway("https://erp.example.com/K3Cloud/");
        properties.setDbId("db-001");
        properties.setUserName("api-user");
        properties.setAppId("app-id");
        properties.setAppSecret("app-secret");
        properties.setLcid(2052);
        properties.setAccountBookNumber("001");
        properties.setVoucherGroupNumber("PRE001");
        properties.setCurrencyNumber("PRE001");
        properties.setExchangeRateTypeNumber("HLTX01_SYS");
        client = new KingdeeRealClient(http, new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("同步前按 FBillNo 查询：已存在则回读原凭证号，禁止重复 Save")
    void existingVoucherIsReturnedWithoutDuplicateSave() {
        when(http.postForEntity(eq("kingdee"), contains(LOGIN_PATH), anyMap(), anyString()))
                .thenReturn(loginResponse("session-a", "asp-a"));
        when(http.postForEntity(eq("kingdee"), contains(QUERY_PATH), anyMap(), anyString()))
                .thenReturn(jsonResponse("[[\"V-005\",1005]]"));

        String number = client.syncVoucher(voucher("V-005"));

        assertEquals("V-005", number);
        verify(http, never()).postForEntityOnce(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    @DisplayName("配置缺失：同步显式失败，绝不返回 Mock 单号")
    void missingConfigurationFailsClosed() {
        properties.setDbId("");

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> client.syncVoucher(voucher("V-001")));

        assertEquals(ConnectorException.Reason.NOT_CONFIGURED, error.getReason());
        assertTrue(error.getMessage().contains("kingdee.db-id"));
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("登录成功：提取 Cookie 并复用会话保存 GL_VOUCHER，返回真实 Result.Number")
    void loginThenSaveReturnsRealVoucherNumber() {
        when(http.postForEntity(eq("kingdee"), contains(LOGIN_PATH), anyMap(), anyString()))
                .thenReturn(loginResponse("session-a", "asp-a"));
        when(http.postForEntity(eq("kingdee"), contains(QUERY_PATH), anyMap(), anyString()))
                .thenReturn(jsonResponse("[]"));
        when(http.postForEntityOnce(eq("kingdee"), contains(SAVE_PATH), anyMap(), anyString()))
                .thenReturn(jsonResponse(successSaveResponse("KD-1001")));

        String first = client.syncVoucher(voucher("V-001"));
        String second = client.syncVoucher(voucher("V-002"));

        assertEquals("KD-1001", first);
        assertEquals("KD-1001", second);
        verify(http).postForEntity(eq("kingdee"), contains(LOGIN_PATH), anyMap(), anyString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(http, org.mockito.Mockito.times(2)).postForEntityOnce(
                eq("kingdee"), contains(SAVE_PATH), headersCaptor.capture(), bodyCaptor.capture());

        assertEquals("kdservice-sessionid=session-a; ASP.NET_SessionId=asp-a",
                headersCaptor.getAllValues().get(0).get(HttpHeaders.COOKIE));
        assertTrue(bodyCaptor.getAllValues().get(0).contains("\"formid\":\"GL_VOUCHER\""));
        assertTrue(bodyCaptor.getAllValues().get(0).contains("\"FAccountBookID\":{\"FNumber\":\"001\"}"));
        assertTrue(bodyCaptor.getAllValues().get(0).contains("\"FBillNo\":\"V-001\""));
        assertTrue(bodyCaptor.getAllValues().get(0).contains("\"FDEBIT\":725.00"));
    }

    @Test
    @DisplayName("登录返回 LoginResultType != 1：显式失败，不调用保存")
    void loginFailureDoesNotCallSave() {
        when(http.postForEntity(eq("kingdee"), contains(LOGIN_PATH), anyMap(), anyString()))
                .thenReturn(jsonResponse("{\"LoginResultType\":0,\"Message\":\"账号或应用无效\"}"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.syncVoucher(voucher("V-002")));

        assertTrue(error.getMessage().contains("账号或应用无效"));
        verify(http, never()).postForEntityOnce(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    @DisplayName("保存响应 IsSuccess=false：显式失败，不伪造凭证号")
    void saveBusinessFailureThrows() {
        when(http.postForEntity(eq("kingdee"), contains(LOGIN_PATH), anyMap(), anyString()))
                .thenReturn(loginResponse("session-a", "asp-a"));
        when(http.postForEntity(eq("kingdee"), contains(QUERY_PATH), anyMap(), anyString()))
                .thenReturn(jsonResponse("[]"));
        when(http.postForEntityOnce(eq("kingdee"), contains(SAVE_PATH), anyMap(), anyString()))
                .thenReturn(jsonResponse("{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,"
                        + "\"Errors\":[{\"Message\":\"日期不在当前会计期间\"}]}}}"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.syncVoucher(voucher("V-003")));

        assertTrue(error.getMessage().contains("日期不在当前会计期间"));
    }

    @Test
    @DisplayName("会话失效：清缓存并最多重新登录一次，第二次保存成功")
    void sessionExpiryReloginsAtMostOnce() {
        when(http.postForEntity(eq("kingdee"), contains(LOGIN_PATH), anyMap(), anyString()))
                .thenReturn(loginResponse("session-a", "asp-a"))
                .thenReturn(loginResponse("session-b", "asp-b"));
        when(http.postForEntity(eq("kingdee"), contains(QUERY_PATH), anyMap(), anyString()))
                .thenReturn(jsonResponse("[]"));
        when(http.postForEntityOnce(eq("kingdee"), contains(SAVE_PATH), anyMap(), anyString()))
                .thenReturn(jsonResponse("{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,"
                                + "\"Errors\":[{\"Message\":\"会话信息已丢失，请重新登录\"}]}}}"))
                .thenReturn(jsonResponse(successSaveResponse("KD-1002")));

        String number = client.syncVoucher(voucher("V-004"));

        assertEquals("KD-1002", number);
        verify(http, org.mockito.Mockito.times(2))
                .postForEntity(eq("kingdee"), contains(LOGIN_PATH), anyMap(), anyString());
        verify(http, org.mockito.Mockito.times(2))
                .postForEntityOnce(eq("kingdee"), contains(SAVE_PATH), anyMap(), anyString());
    }

    @Test
    @DisplayName("非 CNY 凭证：多币种契约未验证前必须拒绝，禁止按人民币误入账")
    void nonCnyVoucherFailsClosedBeforeHttp() {
        AccountingVoucher voucher = voucher("V-USD-001");
        voucher.setOriginalAmount(new BigDecimal("100.00"));
        voucher.setExchangeRate(new BigDecimal("7.25"));
        voucher.setCurrency("USD");

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> client.syncVoucher(voucher));

        assertTrue(error.getMessage().contains("当前仅支持 CNY"));
        assertTrue(error.getMessage().contains("currency=USD"));
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("CNY 凭证原币金额与本位币金额不一致：显式失败，防止错误账务")
    void mismatchedCnyAmountFailsClosed() {
        AccountingVoucher voucher = voucher("V-CNY-001");
        voucher.setOriginalAmount(new BigDecimal("100.00"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client.syncVoucher(voucher));

        assertTrue(error.getMessage().contains("原币金额必须等于本位币金额"));
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("CNY 凭证汇率不为 1：显式失败，防止错误账务")
    void inconsistentCnyExchangeRateFailsClosed() {
        AccountingVoucher voucher = voucher("V-CNY-002");
        voucher.setOriginalAmount(new BigDecimal("725.00"));
        voucher.setExchangeRate(new BigDecimal("7.25"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client.syncVoucher(voucher));

        assertTrue(error.getMessage().contains("汇率必须为 1"));
        verifyNoInteractions(http);
    }

    private AccountingVoucher voucher(String voucherNo) {
        AccountingVoucher voucher = new AccountingVoucher();
        voucher.setVoucherNo(voucherNo);
        voucher.setBizDate("2026-09-25");
        voucher.setSummary("亚马逊订单结算");
        voucher.setDebitAccount("1122");
        voucher.setCreditAccount("6001");
        voucher.setCnyAmount(new BigDecimal("725.00"));
        voucher.setCurrency("CNY");
        return voucher;
    }

    private ResponseEntity<String> loginResponse(String sessionId, String aspSessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, "kdservice-sessionid=" + sessionId + "; Path=/; HttpOnly");
        headers.add(HttpHeaders.SET_COOKIE, "ASP.NET_SessionId=" + aspSessionId + "; Path=/; HttpOnly");
        return new ResponseEntity<>("{\"LoginResultType\":1}", headers, HttpStatus.OK);
    }

    private ResponseEntity<String> jsonResponse(String body) {
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    private String successSaveResponse(String number) {
        return "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true,\"Errors\":[],"
                + "\"SuccessEntitys\":[{\"Id\":1001,\"Number\":\"" + number + "\"}]},"
                + "\"Id\":1001,\"Number\":\"" + number + "\"}}";
    }
}