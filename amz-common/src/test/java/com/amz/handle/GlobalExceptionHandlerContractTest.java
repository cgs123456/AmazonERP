package com.amz.handle;

import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局异常处理器契约：service 层 fail-closed 的业务拒绝不得被吞成「服务器内部错误」。
 * <p>
 * IllegalArgumentException 已登记进业务异常组（此前「工单不存在：id=x」这类
 * 拒绝全部变成 500，被拒方拿不到结论）；其余 RuntimeException 仍必须
 * 兜底成不泄露内部信息的服务器内部错误。
 */
@DisplayName("全局异常处理器契约：业务拒绝透出原因，系统异常保持掩码")
class GlobalExceptionHandlerContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("IllegalArgumentException 按业务异常处理：message 原样透出，code 400")
    void illegalArgumentIsBusinessFailure() {
        Result<String> result = handler.businessException(
                new IllegalArgumentException("工单不存在：id=9"));

        assertEquals(400, result.getCode());
        assertEquals("工单不存在：id=9", result.getMessage());
    }

    @Test
    @DisplayName("其余 RuntimeException 仍兜底为不泄露内部信息的服务器内部错误")
    void otherRuntimeErrorsStayMasked() {
        Result<String> result = handler.runtimeException(new IllegalStateException("db conn leaked"));

        assertEquals(400, result.getCode());
        assertEquals("服务器内部错误", result.getMessage());
    }
}
