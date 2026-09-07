package com.amz.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 工具执行事件（SSE 推送载荷）。
 * <p>
 * 事件序列：{@code round_started} → {@code tool_call} → {@code tool_result} →（循环）→
 * {@code final} → {@code done}；异常走 {@code error}。result/summary 均截断，
 * 避免大 JSON 撑爆 SSE 帧。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolEvent {

    /** 事件类型：round_started/tool_call/tool_result/final/done/error */
    private String type;

    /** 工具名（tool_call/tool_result） */
    private String name;

    /** 调用参数摘要（tool_call，截断） */
    private String argsSummary;

    /** 工具是否成功（tool_result） */
    private Boolean ok;

    /** 工具耗时毫秒（tool_result） */
    private Long elapsedMs;

    /** 结果摘要（tool_result，截断） */
    private String summary;

    /** 最终回复全文（final） */
    private String content;

    /** 错误信息（error） */
    private String message;

    public static AgentToolEvent roundStarted(String traceId) {
        AgentToolEvent e = new AgentToolEvent();
        e.setType("round_started");
        e.setSummary(traceId);
        return e;
    }

    public static AgentToolEvent call(String name, String argsSummary) {
        AgentToolEvent e = new AgentToolEvent();
        e.setType("tool_call");
        e.setName(name);
        e.setArgsSummary(argsSummary);
        return e;
    }

    public static AgentToolEvent result(String name, boolean ok, long elapsedMs, String summary) {
        AgentToolEvent e = new AgentToolEvent();
        e.setType("tool_result");
        e.setName(name);
        e.setOk(ok);
        e.setElapsedMs(elapsedMs);
        e.setSummary(summary);
        return e;
    }

    public static AgentToolEvent finalAnswer(String content) {
        AgentToolEvent e = new AgentToolEvent();
        e.setType("final");
        e.setContent(content);
        return e;
    }

    public static AgentToolEvent done() {
        AgentToolEvent e = new AgentToolEvent();
        e.setType("done");
        return e;
    }

    public static AgentToolEvent error(String message) {
        AgentToolEvent e = new AgentToolEvent();
        e.setType("error");
        e.setMessage(message);
        return e;
    }
}
