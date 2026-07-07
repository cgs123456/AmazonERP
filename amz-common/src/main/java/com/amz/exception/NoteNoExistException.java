package com.amz.exception;

public class NoteNoExistException extends RuntimeException {

    public NoteNoExistException(String message) {
        super(message);
    }
}
