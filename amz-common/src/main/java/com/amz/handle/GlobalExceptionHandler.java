package com.amz.handle;

import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.exception.ConnectorException;
import com.amz.exception.InvalidParamException;
import com.amz.exception.UserNoExistException;
import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

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
     * {@link IllegalArgumentException} 同理：service 层的「工单不存在：id=x」一类
     * fail-closed 拒绝都用它表达，不登记就会全部变成 500。代价是三方库的编程错误
     * 也会把 message 透出——本仓约定 service 层只把<b>面向用户的业务原因</b>放进去。
     * <p>
     * 返回码仍是 {@code Result.failure} 的 400，与其余业务异常一致，前端无需适配。
     */
    @ExceptionHandler({UserNoExistException.class, CodeErrorException.class, ConnectorException.class,
            AttrIsNullException.class, InvalidParamException.class, IllegalArgumentException.class})
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
     * 处理运行时异常（不暴露内部信息）。
     * <p>
     * <b>HTTP 500</b>：这是「我们这边出错了」，不是「你的请求有问题」。
     * 此前它和业务异常一样返回 HTTP 200 + {@code code=400}，第三方系统无法按状态码区分
     * 「请求被拒」与「服务端故障」，只能解析 body——而 body 契约不是所有调用方都会看。
     * <p>
     * 只把<b>系统级兜底</b>映射成 5xx：业务校验失败与「无权限操作」保持 HTTP 200 + code=400，
     * 因为前端有 60+ 处按 {@code code === 200} 判断、契约测试也有 30 处断言 HTTP 200，
     * 全面改成 4xx/5xx 是一次全站契约变更，风险与收益不成比例（见 HANDOFF 决策）。
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<String> runtimeException(RuntimeException e) {
        log.error("运行时异常", e);
        return Result.failure("服务器内部错误");
    }

    /**
     * 处理系统异常（最终兜底，不暴露内部信息）。HTTP 500，理由同上。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<String> exception(Exception e) {
        log.error("系统异常", e);
        return Result.failure("服务器内部错误");
    }
}
