package com.amz.exception;

/**
 * 上传文件为空异常
 */
public class FileIsNullException extends RuntimeException {
    public FileIsNullException(String message) {
        super(message);
    }
}
