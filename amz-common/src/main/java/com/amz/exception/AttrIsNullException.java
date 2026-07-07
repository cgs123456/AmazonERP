package com.amz.exception;

/**
 * 属性为空异常
 */
public class AttrIsNullException extends RuntimeException {
    public AttrIsNullException(String message) {
        super(message);
    }
}
