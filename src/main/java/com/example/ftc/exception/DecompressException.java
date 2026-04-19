package com.example.ftc.exception;

public class DecompressException extends RuntimeException {

    public DecompressException(String message) {
        super(message);
    }

    public DecompressException(String message, Throwable cause) {
        super(message, cause);
    }
}
