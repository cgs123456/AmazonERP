package com.amz.exception;

/**
 * 属性为空异常
 */
public class AttriIsNullException extends RuntimeException {
    public AttriIsNullException(String message) {
        super(message);
    }
}
