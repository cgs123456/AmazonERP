package com.amz.agent.dto;

import lombok.Data;
import java.util.Map;

/**
 * 表示 LLM 输出的函数调用
 */
@Data
public class FunctionCall {
    /** 工具名称，如 search_products */
    private String name;
    /** 工具参数，如 {"keyword":"红色"} */
    private Map<String, Object> arguments;
}
