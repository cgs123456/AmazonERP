package com.amz.agent;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * IM 推送服务（B-3）：把主动提醒推送到企微/飞书群机器人。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>渠道由 {@code im.webhook-kind} 选择（feishu / wecom / generic），
 *       payload 构造为纯函数，可单测；</li>
 *   <li>webhook URL 为空时静默跳过（dev 默认无害）；</li>
 *   <li>逐条 try/catch，单条失败不阻断后续，永不抛异常到调度器；</li>
 *   <li>复用模块共享 {@code aiOkHttpClient} 连接池，不新建客户端。</li>
 * </ul>
 */
@Slf4j
@Service
public class ImPushService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${im.webhook-url:}")
    private String webhookUrl;

    @Value("${im.webhook-kind:generic}")
    private String webhookKind;

    @Autowired
    private OkHttpClient aiOkHttpClient;

    /**
     * 批量推送提醒，返回成功条数（失败仅记日志）。
     */
    public int pushAll(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("IM webhook 未配置，跳过推送 {} 条", messages.size());
            return 0;
        }
        int sent = 0;
        for (String msg : messages) {
            if (msg == null || msg.isBlank()) {
                continue;
            }
            try {
                if (postText(msg)) {
                    sent++;
                }
            } catch (Exception e) {
                log.warn("IM 推送失败 kind={} msgLen={}", webhookKind, msg.length(), e);
            }
        }
        log.info("IM 推送完成 kind={} 成功 {}/{}", webhookKind, sent, messages.size());
        return sent;
    }

    private boolean postText(String text) throws Exception {
        String payload = buildPayload(webhookKind, text);
        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(RequestBody.create(payload, JSON))
                .build();
        try (Response response = aiOkHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("IM 推送 HTTP 非 2xx kind={} code={}", webhookKind, response.code());
                return false;
            }
            return true;
        }
    }

    /**
     * 按渠道构造文本消息载荷（纯函数，可单测）。
     * 未知 kind 回退 generic（{"text": "..."}）。
     */
    static String buildPayload(String kind, String text) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase();
        String escaped = escapeJson(text);
        switch (normalized) {
            case "feishu":
                return "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + escaped + "\"}}";
            case "wecom":
                return "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escaped + "\"}}";
            default:
                return "{\"text\":\"" + escaped + "\"}";
        }
    }

    /**
     * 最小 JSON 字符串转义（本载荷仅含可控文本字段，不引入整套 JSON 库依赖链）。
     */
    static String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
