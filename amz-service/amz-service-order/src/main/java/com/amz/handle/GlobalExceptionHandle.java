package com.amz.handle;

import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 全局异常处理
 */
@ControllerAdvice
@ResponseBody
@Slf4j
public class GlobalExceptionHandle {
    /**
     * 处理系统异常（不暴露内部信息）
     */
    @ExceptionHandler(value = Exception.class)
    public Result<String> exception(Exception e) {
        log.error("异常信息：{}", e.getMessage());
        return Result.failure("服务器内部错误");
    }
}
