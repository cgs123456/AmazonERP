package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.AmazonMessagingRealClient;
import com.amz.client.MessagingAction;
import com.amz.connector.ErrorSummary;
import com.amz.connector.LocalApiException;
import com.amz.result.Result;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Amazon Messaging API 对外接口。
 *
 * <p>只暴露官方 messaging.json 声明的语义：按订单查询可用动作、查询订单属性、
 * 发送九类受支持消息。官方 API 不提供收件箱列表、单条消息详情、标记已读或任意回复，
 * 因此这里不提供对应端点，避免把不存在的能力包装成可用业务。
 */
@RestController
@RequestMapping("/spapi/messaging")
@Profile("!mock")
public class MessagingController {

    private static final Logger log = LoggerFactory.getLogger(MessagingController.class);
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    @Autowired
    private AmazonMessagingRealClient client;

    @ShopScoped
    @GetMapping("/actions")
    public Result<Map<String, Object>> getActions(@RequestParam Long shopId,
                                                  @RequestParam String amazonOrderId,
                                                  @RequestParam String marketplaceId) {
        try {
            return Result.success(toMap(client.getMessagingActionsForOrder(
                    shopId, amazonOrderId, marketplaceId)));
        } catch (Exception e) {
            log.error("Messaging actions query failed shopId={} amazonOrderId={}", shopId, amazonOrderId, e);
            return Result.failure("messaging actions failed: " + ErrorSummary.of(e),
                    ErrorSummary.toApiError(e));
        }
    }

    @ShopScoped
    @GetMapping("/attributes")
    public Result<Map<String, Object>> getAttributes(@RequestParam Long shopId,
                                                     @RequestParam String amazonOrderId,
                                                     @RequestParam String marketplaceId) {
        try {
            return Result.success(toMap(client.getOrderAttributes(shopId, amazonOrderId, marketplaceId)));
        } catch (Exception e) {
            log.error("Messaging attributes query failed shopId={} amazonOrderId={}", shopId, amazonOrderId, e);
            return Result.failure("messaging attributes failed: " + ErrorSummary.of(e),
                    ErrorSummary.toApiError(e));
        }
    }

    @RequireRole({"OPERATOR", "ADMIN"})
    @ShopScoped
    @PostMapping("/send")
    public Result<Map<String, Object>> send(@RequestParam Long shopId,
                                            @RequestParam String amazonOrderId,
                                            @RequestParam String marketplaceId,
                                            @RequestParam String action,
                                            @RequestBody(required = false) Map<String, Object> body) {
        MessagingAction messagingAction;
        try {
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("action must not be blank");
            }
            messagingAction = MessagingAction.valueOf(action.trim());
        } catch (IllegalArgumentException e) {
            return Result.failure("invalid action: " + action + "; expected one of "
                    + java.util.Arrays.toString(MessagingAction.values()),
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }

        if (body == null) {
            return Result.failure("body must not be null for action=" + messagingAction.name(),
                    ErrorSummary.localError(LocalApiException.CODE_INVALID_REQUEST));
        }

        try {
            JsonObject requestBody = GSON.toJsonTree(body).getAsJsonObject();
            return Result.success(toMap(client.sendMessage(
                    shopId, amazonOrderId, marketplaceId, messagingAction, requestBody)));
        } catch (Exception e) {
            log.error("Messaging send failed shopId={} amazonOrderId={} action={}",
                    shopId, amazonOrderId, messagingAction, e);
            return Result.failure("messaging send failed: " + ErrorSummary.of(e),
                    ErrorSummary.toApiError(e));
        }
    }

    private static Map<String, Object> toMap(JsonObject json) {
        return GSON.fromJson(json, MAP_TYPE);
    }
}
