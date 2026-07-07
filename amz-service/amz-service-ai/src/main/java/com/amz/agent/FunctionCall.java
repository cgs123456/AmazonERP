package com.amz.agent;

import lombok.Data;

import java.util.Map;

/**
 * Function Calling 调用描述（LLM 返回的函数调用）。
 */
@Data
public class FunctionCall {
    private String name;
    private Map<String, Object> arguments;
}
