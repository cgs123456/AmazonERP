package com.amz.handle;

import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 全局异常处理器契约：service 层 fail-closed 的业务拒绝不得被吞成「服务器内部错误」。
 * <p>
 * IllegalArgumentException 已登记进业务异常组（此前「工单不存在：id=x」这类
 * 拒绝全部变成 500，被拒方拿不到结论）；其余 RuntimeException 仍必须
 * 兜底成不泄露内部信息的服务器内部错误。
 * <p>
 * 另钉住 HTTP 状态码口径（2026-10-11 保守改造）：
 * <b>只有系统级兜底（RuntimeException/Exception）映射为 HTTP 500</b>；
 * 业务异常与参数校验失败保持 HTTP 200 + {@code code=400}（前端 60+ 处按 code 判断、
 * 契约测试 30 处断言 HTTP 200，全面改 4xx/5xx 是全站契约变更，风险不成比例）。
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

    @Test
    @DisplayName("HTTP 状态码：只有系统级兜底映射 500，业务异常保持无 @ResponseStatus（默认 200）")
    void onlySystemFallbacksCarryHttp500() throws Exception {
        Method runtime = GlobalExceptionHandler.class.getMethod("runtimeException", RuntimeException.class);
        Method exception = GlobalExceptionHandler.class.getMethod("exception", Exception.class);
        Method business = GlobalExceptionHandler.class.getMethod("businessException", RuntimeException.class);
        Method notValid = GlobalExceptionHandler.class.getMethod(
                "methodArgumentNotValidException",
                org.springframework.web.bind.MethodArgumentNotValidException.class);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                runtime.getAnnotation(ResponseStatus.class).value(),
                "系统级兜底必须是 HTTP 500：调用方要能按状态码区分「请求被拒」与「服务端故障」");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                exception.getAnnotation(ResponseStatus.class).value());

        assertNull(business.getAnnotation(ResponseStatus.class),
                "业务异常必须保持 HTTP 200（默认）+ code=400，改动会破坏 30 处契约断言与前端 60+ 处判断");
        assertNull(notValid.getAnnotation(ResponseStatus.class),
                "参数校验失败同理保持 HTTP 200 + code=400");
        assertNotNull(runtime.getAnnotation(ResponseStatus.class));
    }
}
