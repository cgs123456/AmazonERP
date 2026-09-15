package com.amz.handle;

import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.exception.UserNoExistException;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.stream.Collectors;

/**
 * 全局异常处理（公共模块，所有服务共享）
 */
@ControllerAdvice
@ResponseBody
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理业务异常（自定义异常）
     * <p>
     * {@link AttrIsNullException} 必须一并列出：它继承 {@code RuntimeException} 但并不属于
     * {@link CodeErrorException} 的子类，此前未登记在这里，导致所有「属性为空」的业务提示
     * 都被下面的 {@code RuntimeException} 兜底吞成「服务器内部错误」——
     * 调用方看不到真正原因（例如「调拨单不存在：id=5」），只能去翻服务端日志。
     * <p>
     * 返回码仍是 {@code Result.failure} 的 400，与其余业务异常一致，前端无需适配。
     */
    @ExceptionHandler({UserNoExistException.class, CodeErrorException.class, AttrIsNullException.class})
    public Result<String> businessException(RuntimeException e) {
        log.error("业务异常: {}", e.getMessage());
        return Result.failure(e.getMessage());
    }

    /**
     * 处理参数校验失败（@Valid / @Validated）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<String> methodArgumentNotValidException(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.error("参数校验失败: {}", details);
        return Result.failure("参数校验失败: " + details);
    }

    /**
     * 处理参数绑定失败
     */
    @ExceptionHandler(BindException.class)
    public Result<String> bindException(BindException e) {
        log.error("参数绑定失败: {}", e.getMessage());
        return Result.failure("参数绑定失败");
    }

    /**
     * 处理请求体解析失败（JSON格式错误等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<String> httpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.error("请求参数格式错误: {}", e.getMessage());
        return Result.failure("请求参数格式错误");
    }

    /**
     * 处理运行时异常（不暴露内部信息）
     */
    @ExceptionHandler(RuntimeException.class)
    public Result<String> runtimeException(RuntimeException e) {
        log.error("运行时异常", e);
        return Result.failure("服务器内部错误");
    }

    /**
     * 处理系统异常（最终兜底，不暴露内部信息）
     */
    @ExceptionHandler(Exception.class)
    public Result<String> exception(Exception e) {
        log.error("系统异常", e);
        return Result.failure("服务器内部错误");
    }
}
