package com.amz.agent.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Agent 对话消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {
    /** role: user / assistant */
    private String role;
    /** content: 消息内容 */
    private String content;
}
