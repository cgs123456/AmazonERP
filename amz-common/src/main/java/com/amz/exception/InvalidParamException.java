package com.amz.exception;

/**
 * 参数非法：分页参数越界、游标不可解析等调用方可以自行修正的错误。
 *
 * <p>必须在 {@link com.amz.handle.GlobalExceptionHandler} 中登记为业务异常，
 * 否则会被 RuntimeException 兜底吞成「服务器内部错误」，调用方拿不到
 * 「size 超过上限 500」这类可修复提示，只能去翻服务端日志。
 */
public class InvalidParamException extends RuntimeException {

    public InvalidParamException(String message) {
        super(message);
    }
}
