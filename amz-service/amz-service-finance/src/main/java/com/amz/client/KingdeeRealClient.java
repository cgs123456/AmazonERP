package com.amz.client;

import com.amz.exception.ConnectorException;
import com.amz.http.ResilientHttpClient;
import com.amz.model.AccountingVoucher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 金蝶云星空 Web API 客户端。
 * <p>
 * 使用第三方应用免密登录，成功后复用会话 Cookie 保存总账凭证。
 * 任何配置缺失、登录失败、业务返回失败或响应无法解析都会显式抛异常，
 * 不返回 Mock 单号，也不把未确认的同步标记为成功。
 * <p>
 * 凭证保存属于非幂等写操作，因此使用 {@code postForEntityOnce}，网络异常时最多发送一次，
 * 避免响应丢失后的自动重试造成重复入账。
 */
@Slf4j
@Component
@Profile("!mock")
public class KingdeeRealClient implements KingdeeClient {

    private static final String LOGIN_PATH =
            "/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc";
    private static final String SAVE_PATH =
            "/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Save.common.kdsvc";
    private static final String QUERY_PATH =
            "/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc";
    private static final String FORM_ID = "GL_VOUCHER";
    private static final String TARGET = "kingdee";

    private final ResilientHttpClient http;
    private final ObjectMapper objectMapper;
    private final KingdeeProperties properties;
    private final Object sessionLock = new Object();

    /** 由 Set-Cookie 或登录响应提取的 Cookie 请求头。 */
    private volatile String sessionCookie;

    public KingdeeRealClient(ResilientHttpClient http, ObjectMapper objectMapper,
                             KingdeeProperties properties) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String syncVoucher(AccountingVoucher voucher) {
        validateConfiguration();
        if (voucher == null) {
            throw new IllegalArgumentException("凭证不能为空");
        }

        String requestBody = buildSaveRequest(voucher);
        try {
            return syncWithSession(requestBody, voucher.getVoucherNo(), ensureSession());
        } catch (SessionExpiredException firstFailure) {
            log.warn("金蝶会话失效，将重新登录一次：voucherNo={}", voucher.getVoucherNo());
            synchronized (sessionLock) {
                sessionCookie = null;
            }
            String refreshedCookie = ensureSession();
            return syncWithSession(requestBody, voucher.getVoucherNo(), refreshedCookie);
        }
    }

    /**
     * 先按本系统唯一凭证号查重，再执行保存。
     * <p>
     * 这样即使上次请求已在金蝶落库但响应丢失，重试也会回读已有凭证号，
     * 而不是再次 Save 造成重复入账。
     */
    private String syncWithSession(String requestBody, String voucherNo, String cookie) {
        String existing = findVoucherNumber(voucherNo, cookie);
        if (existing != null) {
            log.info("金蝶已存在同号凭证，跳过重复保存：voucherNo={} kingdeeNo={}", voucherNo, existing);
            return existing;
        }
        return saveVoucher(requestBody, cookie);
    }

    private String ensureSession() {
        String cached = sessionCookie;
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        synchronized (sessionLock) {
            if (sessionCookie != null && !sessionCookie.isBlank()) {
                return sessionCookie;
            }
            String cookie = login();
            sessionCookie = cookie;
            return cookie;
        }
    }

    private String login() {
        String body;
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("format", 1);
            request.put("useragent", "ApiClient");
            request.put("rid", UUID.randomUUID().toString());
            ArrayNode parameters = request.putArray("parameters");
            parameters.add(properties.getDbId());
            parameters.add(properties.getUserName());
            parameters.add(properties.getAppId());
            parameters.add(properties.getAppSecret());
            parameters.add(properties.getLcid());
            request.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
            request.put("v", "1.0");
            body = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构建金蝶登录请求失败", e);
        }

        ResponseEntity<String> response = http.postForEntity(
                TARGET, endpoint(LOGIN_PATH), Map.of(HttpHeaders.CONTENT_TYPE, "application/json"), body);
        JsonNode root = parseJson(response.getBody(), "登录");
        JsonNode result = root.has("Result") ? root.path("Result") : root;
        int loginResultType = result.path("LoginResultType").asInt(-1);
        if (loginResultType != 1) {
            throw new IllegalStateException("金蝶登录失败："
                    + result.path("Message").asText(result.path("message").asText("LoginResultType != 1")));
        }

        String cookie = extractSessionCookie(response.getHeaders(), result);
        if (cookie.isBlank()) {
            throw new IllegalStateException("金蝶登录成功响应未包含会话 Cookie");
        }
        log.info("金蝶连接器登录成功：dbId={} user={}", properties.getDbId(), properties.getUserName());
        return cookie;
    }

    private String findVoucherNumber(String voucherNo, String cookie) {
        if (voucherNo == null || voucherNo.isBlank()) {
            throw new IllegalArgumentException("凭证号不能为空，无法执行金蝶查重");
        }
        String body;
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("FormId", FORM_ID);
            request.put("FieldKeys", "FBillNo");
            request.put("FilterString", "FBillNo='" + voucherNo.replace("'", "''") + "'");
            request.put("TopRowCount", 1);
            request.put("StartRow", 0);
            request.put("Limit", 1);
            body = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构建金蝶凭证查重请求失败：voucherNo=" + voucherNo, e);
        }

        ResponseEntity<String> response = http.postForEntity(
                TARGET, endpoint(QUERY_PATH),
                Map.of(HttpHeaders.CONTENT_TYPE, "application/json", HttpHeaders.COOKIE, cookie),
                body);
        JsonNode root = parseJson(response.getBody(), "凭证查重");
        if (root.isArray() && root.isEmpty()) {
            return null;
        }
        if (!root.isArray() || !root.get(0).isArray() || root.get(0).isEmpty()) {
            String message = root.path("Message").asText(root.path("message").asText(root.toString()));
            if (isSessionExpiredMessage(message)) {
                throw new SessionExpiredException(message);
            }
            throw new IllegalStateException("金蝶凭证查重响应格式非法：" + root);
        }
        String number = root.get(0).get(0).asText("");
        if (number.isBlank()) {
            throw new IllegalStateException("金蝶凭证查重命中但未返回 FBillNo：" + root);
        }
        return number;
    }

    private String saveVoucher(String requestBody, String cookie) {
        ResponseEntity<String> response = http.postForEntityOnce(
                TARGET, endpoint(SAVE_PATH),
                Map.of(HttpHeaders.CONTENT_TYPE, "application/json", HttpHeaders.COOKIE, cookie),
                requestBody);
        JsonNode root = parseJson(response.getBody(), "保存凭证");
        JsonNode result = root.path("Result");
        JsonNode responseStatus = result.path("ResponseStatus");
        boolean success = responseStatus.path("IsSuccess").asBoolean(false);
        if (!success) {
            String message = errorMessage(responseStatus);
            if (isSessionExpiredMessage(message)) {
                throw new SessionExpiredException(message);
            }
            throw new IllegalStateException("金蝶保存凭证失败：" + message);
        }

        String number = result.path("Number").asText("");
        if (number.isBlank()) {
            JsonNode successEntities = responseStatus.path("SuccessEntitys");
            if (successEntities.isArray() && !successEntities.isEmpty()) {
                number = successEntities.get(0).path("Number").asText("");
            }
        }
        if (number.isBlank()) {
            throw new IllegalStateException("金蝶保存凭证返回成功但缺少 Result.Number");
        }
        return number;
    }

    private String buildSaveRequest(AccountingVoucher voucher) {
        validateSupportedCurrency(voucher);
        if (voucher.getCnyAmount() == null) {
            throw new IllegalArgumentException("凭证本位币金额不能为空：voucherNo=" + voucher.getVoucherNo());
        }
        LocalDate businessDate = parseBusinessDate(voucher.getBizDate());
        String explanation = voucher.getSummary() == null || voucher.getSummary().isBlank()
                ? voucher.getSourceType() : voucher.getSummary();

        ObjectNode data = objectMapper.createObjectNode();
        data.putArray("NeedUpDateFields");
        data.putArray("NeedReturnFields");
        data.put("IsDeleteEntry", "true");
        data.put("SubSystemId", "");
        data.put("IsVerifyBaseDataField", "false");
        data.put("IsEntryBatchFill", "true");
        data.put("ValidateFlag", "true");
        data.put("NumberSearch", "true");
        data.put("IsAutoAdjustField", "true");
        data.put("InterationFlags", "");
        data.put("IgnoreInterationFlag", "");
        data.put("IsControlPrecision", "false");
        data.put("ValidateRepeatJson", "false");

        ObjectNode model = data.putObject("Model");
        model.put("FVOUCHERID", 0);
        model.put("FBillNo", voucher.getVoucherNo());
        model.set("FAccountBookID", fNumber(properties.getAccountBookNumber()));
        model.put("FDate", businessDate + " 00:00:00");
        model.put("FBUSDATE", businessDate + " 00:00:00");
        model.put("FYEAR", businessDate.getYear());
        model.put("FPERIOD", businessDate.getMonthValue());
        model.set("FVOUCHERGROUPID", fNumber(properties.getVoucherGroupNumber()));

        ArrayNode entries = model.putArray("FEntity");
        entries.add(voucherEntry(explanation, voucher.getDebitAccount(), voucher.getCnyAmount(),
                voucher.getCnyAmount(), BigDecimal.ZERO));
        entries.add(voucherEntry(explanation, voucher.getCreditAccount(), voucher.getCnyAmount(),
                BigDecimal.ZERO, voucher.getCnyAmount()));

        ObjectNode request = objectMapper.createObjectNode();
        request.put("formid", FORM_ID);
        request.set("data", data);
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构建金蝶凭证请求失败：voucherNo=" + voucher.getVoucherNo(), e);
        }
    }

    /**
     * 在官方多币种字段语义经真实 K3 环境验证前，只允许本位币 CNY 入账。
     * <p>当前 FDEBIT/FCREDIT 与 FAMOUNTFOR 均写入 cnyAmount，且汇率固定为 1；
     * 若把 USD/EUR 等原币金额按此结构提交，会造成账务金额和币种严重失真。
     */
    private void validateSupportedCurrency(AccountingVoucher voucher) {
        String currency = voucher.getCurrency();
        if (currency != null && !currency.isBlank() && !"CNY".equalsIgnoreCase(currency.trim())) {
            throw new UnsupportedOperationException(
                    "金蝶多币种字段映射尚未完成真实环境验证，当前仅支持 CNY；voucherNo="
                            + voucher.getVoucherNo() + ", currency=" + currency);
        }
        if (voucher.getOriginalAmount() != null
                && voucher.getCnyAmount() != null
                && voucher.getOriginalAmount().compareTo(voucher.getCnyAmount()) != 0) {
            throw new IllegalArgumentException(
                    "CNY 凭证的原币金额必须等于本位币金额：voucherNo=" + voucher.getVoucherNo());
        }
        if (voucher.getExchangeRate() != null
                && voucher.getExchangeRate().compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException(
                    "CNY 凭证汇率必须为 1：voucherNo=" + voucher.getVoucherNo());
        }
    }

    private ObjectNode voucherEntry(String explanation, String accountNumber, BigDecimal amount,
                                    BigDecimal debit, BigDecimal credit) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("凭证科目编码不能为空");
        }
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("FEXPLANATION", explanation);
        entry.set("FACCOUNTID", fNumber(accountNumber));
        entry.set("FCURRENCYID", fNumber(properties.getCurrencyNumber()));
        if (properties.getExchangeRateTypeNumber() != null
                && !properties.getExchangeRateTypeNumber().isBlank()) {
            entry.set("FEXCHANGERATETYPE", fNumber(properties.getExchangeRateTypeNumber()));
        }
        entry.put("FEXCHANGERATE", BigDecimal.ONE);
        entry.put("FAMOUNTFOR", amount);
        entry.put("FDEBIT", debit);
        entry.put("FCREDIT", credit);
        entry.put("FEXPORTENTRYID", 0);
        return entry;
    }

    private ObjectNode fNumber(String number) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("FNumber", number);
        return node;
    }

    private LocalDate parseBusinessDate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("凭证业务日期不能为空");
        }
        String normalized = value.trim();
        try {
            return normalized.length() > 10
                    ? LocalDate.parse(normalized.substring(0, 10))
                    : LocalDate.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("凭证业务日期格式非法，应为 yyyy-MM-dd：" + value, e);
        }
    }

    private JsonNode parseJson(String body, String action) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("金蝶" + action + "响应为空");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("金蝶" + action + "响应不是合法 JSON", e);
        }
    }

    private String extractSessionCookie(HttpHeaders headers, JsonNode loginResult) {
        Map<String, String> cookies = new LinkedHashMap<>();
        List<String> setCookies = headers.getOrDefault(HttpHeaders.SET_COOKIE, List.of());
        for (String setCookie : setCookies) {
            if (setCookie == null || setCookie.isBlank()) {
                continue;
            }
            String pair = setCookie.split(";", 2)[0].trim();
            int equals = pair.indexOf('=');
            if (equals > 0) {
                cookies.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        putCookieFromBody(cookies, "kdservice-sessionid", loginResult, "KDSVCSessionId");
        putCookieFromBody(cookies, "ASP.NET_SessionId", loginResult, "SessionId");
        return cookies.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private void putCookieFromBody(Map<String, String> cookies, String cookieName,
                                   JsonNode body, String jsonField) {
        String value = body.path(jsonField).asText("");
        if (!value.isBlank() && !cookies.containsKey(cookieName)) {
            cookies.put(cookieName, value);
        }
    }

    private String errorMessage(JsonNode responseStatus) {
        JsonNode errors = responseStatus.path("Errors");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            for (JsonNode error : errors) {
                String message = error.path("Message").asText("");
                if (!message.isBlank()) {
                    messages.add(message);
                }
            }
            if (!messages.isEmpty()) {
                return String.join("; ", messages);
            }
        }
        return responseStatus.path("Message").asText("未知错误");
    }

    private boolean isSessionExpiredMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return normalized.contains("会话")
                || normalized.contains("重新登录")
                || (normalized.contains("session")
                    && (normalized.contains("lost") || normalized.contains("expired")
                        || normalized.contains("invalid") || normalized.contains("timeout")));
    }

    private String endpoint(String path) {
        return properties.getApiGateway().replaceAll("/+$", "") + path;
    }

    private void validateConfiguration() {
        List<String> missing = properties.missingRequiredKeys();
        if (!missing.isEmpty()) {
            throw ConnectorException.notConfigured(TARGET,
                    "金蝶连接器配置缺失，禁止降级执行: " + String.join(", ", missing));
        }
    }

    private static final class SessionExpiredException extends RuntimeException {
        private SessionExpiredException(String message) {
            super(message);
        }
    }
}
